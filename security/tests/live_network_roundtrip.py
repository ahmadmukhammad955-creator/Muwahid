"""Real HTTP/Storage interoperability test, synthetic accounts only.
Requires tink==1.13.0 and cryptography. Credentials are external, never committed.
Android-specific behavior is covered separately by LiveCryptoChecks instrumentation.
"""
import base64,hashlib,json,os,pathlib,sys,threading,time,urllib.request,urllib.error,uuid
import tink
from tink import aead,proto_keyset_format,secret_key_access
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

aead.register()
p=pathlib.Path(sys.argv[1]);state=json.loads(p.read_text());accounts=state['accounts']
assert len(accounts)==3 and all(a['email'].startswith('muwahid-live-test-'+state['run']+'-') and a['email'].endswith('@example.invalid') for a in accounts), 'Only dedicated synthetic accounts are allowed'
URL='https://pucxqnegbnjzawzwnpjh.supabase.co';APIKEY=os.environ['MUWAHID_TEST_PUBLISHABLE_KEY']
reports=[];objects=[];stop=threading.Event();joined=set();room=None

def save():p.write_text(json.dumps(state))
def check(name,value):
 if not value:raise AssertionError(name)
 reports.append(name);print('PASS '+name,flush=True)
def http(account,path,data=None,method=None,raw=False):
 headers={'apikey':APIKEY,'Cache-Control':'no-cache'}
 if account is not None:headers['Authorization']='Bearer '+account['session']['access_token']
 if data is not None:
  headers['Content-Type']='application/octet-stream' if raw else 'application/json'
  if raw:headers['Cache-Control']='max-age=0'
  payload=data if raw else json.dumps(data).encode()
 else:payload=None
 req=urllib.request.Request(URL+path,data=payload,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=25) as r:code=r.status;body=r.read()
 except urllib.error.HTTPError as e:code=e.code;body=e.read()
 if raw:return code,body
 try:return code,json.loads(body)
 except ValueError:return code,{}
def rpc(a,fn,args):
 code,result=http(a,'/rest/v1/rpc/'+fn,args)
 if code!=200:raise RuntimeError(fn+' HTTP '+str(code)+' '+str(result.get('message','')))
 return result
def b64(x):return base64.b64encode(x).decode()
def shared(a,b,chat):
 return HKDF(algorithm=hashes.SHA256(),length=32,salt=hashlib.sha256(('Muwahid:E2EE:salt:'+chat).encode()).digest(),info=b'Muwahid:E2EE:chat-key:v1').derive(a['key'].exchange(ec.ECDH(),b['key'].public_key()))
def wrap(a,b,chat,scope,keyset):
 iv=os.urandom(12);aad=('Muwahid:E2EE:v1:'+chat+':call:v1:'+scope+':key:'+b['id']).encode()
 return 'mw1:'+b64(iv)+':'+b64(AESGCM(shared(a,b,chat)).encrypt(iv,b64(keyset).encode(),aad))
def unwrap(b,a,chat,scope,payload):
 _,iv,cipher=payload.split(':');aad=('Muwahid:E2EE:v1:'+chat+':call:v1:'+scope+':key:'+b['id']).encode()
 return base64.b64decode(AESGCM(shared(b,a,chat)).decrypt(base64.b64decode(iv),base64.b64decode(cipher),aad))
def heartbeat():
 while not stop.wait(8):
  for i in list(joined):
   try:rpc(accounts[i],'mw_room_state',{'p_room':room})
   except Exception:pass

try:
 for a in accounts:
  a['key']=ec.generate_private_key(ec.SECP256R1())
  pub=b64(a['key'].public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
  rpc(a,'mw_e2ee_set_key',{'p_public_key':pub})
 A,B,C=accounts
 chat=rpc(A,'mw_start_chat',{'p_person':B['id']})
 created=rpc(A,'mw_live_room_create',{'p_title':'Synthetic encryption '+state['run'][:8],'p_mode':'audio','p_capacity':8,'p_access':'closed'})
 room=created['id'];state['room']=room
 # Save cleanup identifiers without serializing cryptographic runtime objects.
 plain={k:v for k,v in state.items() if k!='accounts'};plain['accounts']=[{k:v for k,v in a.items() if k!='key'} for a in accounts];p.write_text(json.dumps(plain))
 joined.add(0);thread=threading.Thread(target=heartbeat,daemon=True);thread.start()
 waiting=rpc(B,'mw_room_join',{'p_room':room});check('closed room queues new participant',waiting.get('status')=='waiting')
 code,_=http(B,'/rest/v1/rpc/mw_room_secure_recipients',{'p_room':room});check('waiting participant denied keys',code>=400)
 rpc(A,'mw_room_admit',{'p_room':room,'p_user':B['id']});joined.add(1)
 recipients=rpc(A,'mw_room_secure_recipients',{'p_room':room});check('exact two-recipient snapshot',len(recipients)==2)
 nonce=str(uuid.uuid4());ids=sorted([A['id'],B['id']]);scope='Muwahid:LIVE:v1:'+room+':'+A['id']+':'+nonce+':'+','.join(ids)
 handle=tink.new_keyset_handle(aead.aead_key_templates.AES256_GCM);keyset=proto_keyset_format.serialize(handle,secret_key_access.TOKEN);primitive=handle.primitive(aead.Aead)
 envelope={'v':1,'nonce':nonce,'keys':{A['id']:{'wrapped':b64(os.urandom(128))},B['id']:{'chat':chat,'wrapped':wrap(A,B,chat,scope,keyset)}}}
 path=room+'/e2ee/'+str(uuid.uuid4())+'.bin';raw=b'Synthetic private LIVE photo/video payload\x00\xff'*2048
 encrypted=primitive.encrypt(raw,(scope+':file:'+path).encode())
 code,_=http(A,'/storage/v1/object/mw-room-media/'+path,encrypted,raw=True);check('ciphertext upload succeeds',code==200)
 objects.append(path);plain['objects']=objects;p.write_text(json.dumps(plain))
 envelope['path']=path;clear={'kind':'image','body':'Synthetic private LIVE text','media':{'path':path,'mime':'image/jpeg','name':'Private test.jpg','size':len(raw)}}
 body='mwl1:'+b64(primitive.encrypt(json.dumps(clear).encode(),(scope+':message').encode()))
 args={'p_room':room,'p_kind':'image','p_body':body,'p_media':envelope,'p_recipients':recipients}
 message=rpc(A,'mw_room_secure_send',args);check('server stores ciphertext unchanged',message['body']==body and 'Private test.jpg' not in json.dumps(message))
 received=rpc(B,'mw_room_messages_get',{'p_room':room,'p_after':0})[-1]
 recovered=unwrap(B,A,chat,scope,received['media']['keys'][B['id']]['wrapped']);peer=proto_keyset_format.parse(recovered,secret_key_access.TOKEN).primitive(aead.Aead)
 decrypted=json.loads(peer.decrypt(base64.b64decode(received['body'][5:]),(scope+':message').encode()));check('recipient decrypts exact text and metadata',decrypted==clear)
 code,downloaded=http(B,'/storage/v1/object/authenticated/mw-room-media/'+path,raw=True);check('recipient decrypts exact Storage bytes',code==200 and peer.decrypt(downloaded,(scope+':file:'+path).encode())==raw)
 for a,label in [(C,'outsider'),(None,'anonymous')]:
  code,_=http(a,'/rest/v1/rpc/mw_room_secure_media_get',{'p_room':room,'p_path':path});check(label+' denied file envelope',code>=400)
  code,_=http(a,'/storage/v1/object/authenticated/mw-room-media/'+path,raw=True);check(label+' denied Storage file',code>=400)
 code,_=http(A,'/rest/v1/rpc/mw_room_secure_send',dict(args,p_body='plaintext'));check('plaintext rejected by secure RPC',code>=400)
 code,_=http(A,'/rest/v1/rpc/mw_room_secure_send',args);check('replayed item rejected',code>=400)
 rpc(A,'mw_room_kick',{'p_room':room,'p_user':B['id'],'p_ban':True});joined.discard(1)
 code,_=http(A,'/rest/v1/rpc/mw_room_secure_send',args);check('stale recipient snapshot rejected after removal',code>=400)
 code,_=http(B,'/rest/v1/rpc/mw_room_secure_media_get',{'p_room':room,'p_path':path});check('removed participant denied file envelope',code>=400)
 code,_=http(B,'/storage/v1/object/authenticated/mw-room-media/'+path,raw=True)
 print('OBSERVED prior downloaded ciphertext HTTP '+str(code)+' after removal (CDN may retain previously authorized bytes)',flush=True)
 fresh=room+'/e2ee/'+str(uuid.uuid4())+'.bin'
 code,_=http(A,'/storage/v1/object/mw-room-media/'+fresh,encrypted,raw=True);check('new encrypted object uploaded after removal',code==200);objects.append(fresh);plain['objects']=objects;p.write_text(json.dumps(plain))
 code,_=http(B,'/storage/v1/object/authenticated/mw-room-media/'+fresh,raw=True);check('removed participant denied new Storage object',code>=400)
 code,_=http(B,'/rest/v1/rpc/mw_room_messages_get',{'p_room':room,'p_after':0});check('removed participant denied room history',code>=400)
 after=rpc(A,'mw_room_secure_recipients',{'p_room':room});check('new keys exclude removed participant',len(after)==1 and after[0]['id']==A['id'])
finally:
 stop.set()
 if room:
  for path in objects:
   try:
    code,_=http(accounts[0],'/storage/v1/object/mw-room-media',{'prefixes':[path]},method='DELETE')
    print('CLEANUP storage HTTP '+str(code),flush=True)
   except Exception as e:print('CLEANUP storage pending '+type(e).__name__,flush=True)
  try:rpc(accounts[0],'mw_room_end',{'p_room':room});print('CLEANUP room ended',flush=True)
  except Exception as e:print('CLEANUP room pending '+type(e).__name__,flush=True)
 pathlib.Path(sys.argv[1]).with_name('network-result.json').write_text(json.dumps({'passed':reports,'count':len(reports)},indent=2))
print('LIVE_NETWORK_OK '+str(len(reports)),flush=True)

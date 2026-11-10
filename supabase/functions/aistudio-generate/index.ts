import { makeHandler } from './handler.mjs';
Deno.serve(makeHandler((name: string) => Deno.env.get(name)));

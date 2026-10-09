// TEEB server side on Cloudflare Pages ("advanced mode" so a drag-and-drop upload works).
// /api/ai   -> free chat brain (Workers AI)      /api/tool -> draw, describe picture, translate, Luno prices
const J = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { 'content-type': 'application/json' } });
const NOAI = 'Add the Workers AI binding named AI in Cloudflare, then upload the zip again.';

async function ai(request, env) {
  if (!env.AI) return J({ error: NOAI }, 501);
  try {
    const { messages, model } = await request.json();
    const m = (model && model.startsWith('@cf/')) ? model : '@cf/meta/llama-3.3-70b-instruct-fp8-fast';
    const out = await env.AI.run(m, { messages: (messages || []).slice(-14), max_tokens: 600 });
    return J({ text: out.response || '' });
  } catch (e) { return J({ error: String(e.message || e) }, 500); }
}

async function tool(request, env) {
  try {
    const d = await request.json();
    if (d.op === 'luno') {
      if (!/^[A-Z]{5,8}$/.test(d.pair || '')) return J({ error: 'bad pair' }, 400);
      const r = await fetch('https://api.luno.com/api/1/ticker?pair=' + d.pair);
      return J(await r.json(), r.status);
    }
    if (!env.AI) return J({ error: NOAI }, 501);
    if (d.op === 'img') { const o = await env.AI.run('@cf/black-forest-labs/flux-1-schnell', { prompt: String(d.prompt).slice(0, 800), steps: 4 }); return J({ image: o.image }); }
    if (d.op === 'tr') { const o = await env.AI.run('@cf/meta/m2m100-1.2b', { text: String(d.text).slice(0, 2000), source_lang: 'english', target_lang: d.to }); return J({ text: o.translated_text }); }
    if (d.op === 'vision') { const o = await env.AI.run('@cf/llava-hf/llava-1.5-7b-hf', { image: d.image, prompt: String(d.prompt).slice(0, 500), max_tokens: 400 }); return J({ text: o.description || o.response || '' }); }
    return J({ error: 'unknown op' }, 400);
  } catch (e) { return J({ error: String(e.message || e) }, 500); }
}

export default {
  async fetch(request, env) {
    const p = new URL(request.url).pathname;
    if (request.method === 'POST' && p === '/api/ai') return ai(request, env);
    if (request.method === 'POST' && p === '/api/tool') return tool(request, env);
    return env.ASSETS.fetch(request);
  }
};

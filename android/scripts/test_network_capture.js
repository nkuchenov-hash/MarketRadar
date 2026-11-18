const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(
    __dirname + '/../app/src/main/java/com/marketradar/mobile/MarketplaceNetworkCapture.java',
    'utf8');
const match = source.match(/private static final String SCRIPT = """([\s\S]*?)""";/);
assert.ok(match, 'Document-start capture script must exist');
const script = match[1].replace(/^ {8}/gm, '');
const response = {
  ok: true,
  url: 'https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?sort=priceup',
  clone() { return this; },
  async json() { return {data: {products: [
    {id: 123456, name: 'Яндекс Станция Мини 3 Про',
      sizes: [{price: {total: 1077200, product: 1964000}}]},
    {id: 123457, name: 'Яндекс Станция Мини 3',
      sizes: [{price: {total: 1064000}}]}
  ]}}; }
};
const window = { fetch: async () => response };
const context = vm.createContext({window,Promise});
vm.runInContext(script, context, {filename: 'wb-capture.js'});
(async () => {
  const fetched = await window.fetch('/fake');
  assert.equal(fetched, response, 'Capture must not replace marketplace response');
  for(let i=0;i<8 && window.__marketRadarNetworkCards.length===0;i++)
    await new Promise(r=>setTimeout(r,10));
  const cards = window.__marketRadarNetworkCards;
  assert.equal(window.__marketRadarNetworkResponses,1);
  assert.equal(cards.length,2);
  assert.equal(cards[0].price,10772);
  assert.equal(cards[0].url,'https://www.wildberries.ru/catalog/123456/detail.aspx');
  assert.equal(cards[1].price,10640);
  assert.equal(cards[0].title,'Яндекс Станция Мини 3 Про');
  console.log('WB first-party JSON capture: PASS',cards.map(x=>x.price).join(', '));
})().catch(e=>{console.error(e);process.exit(1)});

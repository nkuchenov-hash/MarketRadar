"""Realistic HTML card extraction tests: no nearest-price leakage."""
import unittest
from server.public_html import extract_public_html

class SSRExtractionTests(unittest.TestCase):
    ORIGIN = "https://market.yandex.ru/search?text=phone"

    def test_two_cards_prices_and_images(self):
        html = """
        <main><section>
          <article class="snippet">
            <a href="/card/phone/12345678" aria-label="Phone A 256 GB">Phone A</a>
            <div><span>14 900 ₽</span><span>завтра</span></div>
            <img src="https://example.com/a.webp" alt="Phone A">
          </article>
          <article class="snippet">
            <a href="/card/phone/12345679">Phone B 256 GB</a>
            <div>10 750 ₽</div><img src="https://example.com/b.webp">
          </article>
        </section></main>"""
        r=extract_public_html(html,self.ORIGIN)
        self.assertEqual(2,r["observed_product_anchors"])
        self.assertEqual(2,r["unique_products"])
        self.assertEqual(10750,r["links"][0]["price"])
        self.assertEqual(14900,r["links"][1]["price"])
        self.assertEqual("завтра",r["links"][1]["delivery_hint"])

    def test_yandex_snippet_price_label_does_not_replace_product_title(self):
        html = '''
        <div data-baobab-name="productSnippet"
          data-zone-data="{&quot;deliveryOptions&quot;:[{&quot;type&quot;:&quot;COURIER&quot;,
          &quot;dateFrom&quot;:&quot;2026-11-20&quot;}]}">
          <a href="/card/umnaya-kolonka-yandeks-stantsiya-mini-3-pro/4554877195">
            Цена с картой Яндекс Пэй 12 447 ₽
          </a>
          <h3 data-auto="snippet-title">Умная колонка Яндекс Станция Мини 3 Про</h3>
          <img alt="Умная колонка Яндекс Станция Мини 3 Про"
               src="https://cdn.example.org/item.webp">
        </div>
        '''
        r=extract_public_html(html,self.ORIGIN)
        self.assertEqual(1,r["unique_products"])
        product=r["links"][0]
        self.assertEqual(12447,product["price"])
        self.assertEqual("Умная колонка Яндекс Станция Мини 3 Про",product["title"])
        self.assertEqual("20 ноября",product["delivery_hint"])
        self.assertEqual("https://cdn.example.org/item.webp",product["image_url"])

    def test_bad_price_label_uses_product_slug_as_title(self):
        html = '''
        <article><a href="/card/yandeks-stantsiya-mini-3-pro/4554877195">
        Цена с картой Яндекс Пэй 12 447 ₽</a></article>
        '''
        r=extract_public_html(html,self.ORIGIN)
        self.assertEqual(1,r["unique_products"])
        self.assertIn("Яндекс Станция Мини 3 Про",r["links"][0]["title"])

    def test_shared_price_is_never_attributed_to_each_product(self):
        html = """
        <div id="grid">
          <div><a href="/card/phone/12345678">Phone A</a></div>
          <div><a href="/card/phone/12345679">Phone B</a></div>
          <div>8 500 ₽</div>
        </div>"""
        r=extract_public_html(html,self.ORIGIN)
        self.assertEqual(2,r["unique_products"])
        self.assertEqual([0,0],[p["price"] for p in r["links"]])

    def test_deduplicates_images_and_title_anchors(self):
        html = """
        <article>
          <a href="/card/phone/12345678"><img src="/image.png" alt="Phone A"></a>
          <a href="/card/phone/12345678">Phone A</a>
          <p>13 500 ₽</p>
        </article>"""
        r=extract_public_html(html,self.ORIGIN)
        self.assertEqual(1,r["unique_products"])
        self.assertEqual(13500,r["links"][0]["price"])

if __name__=="__main__":
    unittest.main()

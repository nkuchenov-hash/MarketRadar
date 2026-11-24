"""Live local-model inference acceptance, not mocked decisions.

Run inside the deployed backend: python -m server.probe_identity.
Fixtures exercise classifier behavior, not marketplace access or real prices.
"""
import asyncio
from .semantic_identity import _scores, assess

Q = "Яндекс Станция Мини 3 Про"
CASES = [
    (Q, "Оригинальная умная колонка с Алисой Zigbee 18 Вт", "match"),
    ("Умная колонка Станция Мини 3 Про с Алисой с Zigbee", "Умная колонка, 18 Вт", "match"),
    (Q, "Только чехол, без колонки", "reject"),
    (Q, "В комплекте только климатический модуль. Колонка не входит в комплект.", "reject"),
    ("Чехол для "+Q, "Защитный чехол, колонка не входит в комплект", "reject"),
    ("Умная колонка Яндекс Станция Мини 3", "Умная колонка", "reject"),
    ("Умная колонка Яндекс Станция Мини 3 Про с Алисой", "Колонка и адаптер питания в комплекте", "match"),
]

async def main():
    cards = [{"title":t,"context":c} for t,c,_ in CASES]
    scores = await asyncio.to_thread(_scores,Q,cards)
    decisions = await assess(Q,cards)
    for (title,context,expected), score, got in zip(CASES,scores,decisions):
        print(f"{got} expected={expected} scores={score} title={title!r} context={context!r}",flush=True)
    assert decisions == [expected for _,_,expected in CASES],decisions
    print("IDENTITY ACCEPTANCE PASSED: positive and negative cases",flush=True)

if __name__ == "__main__":
    asyncio.run(main())

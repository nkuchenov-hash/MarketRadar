import importlib.util
from pathlib import Path
p=Path(__file__).parent/"evaluate-retail-acceptance.py"
spec=importlib.util.spec_from_file_location("acceptance",p)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
refs=[];reports=[]
for s in mod.SHOPS:
    for m in mod.MODES:
        row=dict(query="test model",shop=s,mode=m,region="test city",payment_condition="regular",delivery_limit=3,delivery_days=1,exact_model_verified=True,price_verified=True,delivery_verified=True,total_price=1000,observed_at_epoch=1_800_000_000,product_url="https://example.test/fixture")
        refs.append(row)
        reports.append({**row,"finished":True,"source":("ozon_mcp" if s=="ozon" else "specialist") if m=="fast" else "browser_deep","first_offer_seconds":2,"seconds":10})
assert mod.assess(reports,refs,1,0)["accepted"]
assert not mod.assess(reports,refs)["accepted"]
reports[0]["source"]="retail_fast"
result=mod.assess(reports,refs,1,0)
assert not result["accepted"] and result["failure_reasons"]["wrong_engine"]==1
reports[0]["source"]="specialist";reports[0]["price_verified"]=False
assert not mod.assess(reports,refs,1,0)["accepted"]
assert not mod.assess(reports[:1],refs,1,0)["accepted"]
assert not mod.assess(reports,[],1,0)["accepted"]
print("Acceptance rejects browser substitution, unverified prices, missing cases and insufficient sample")

import sys, json, time, urllib.request, urllib.parse, statistics
from concurrent.futures import ThreadPoolExecutor
host=sys.argv[1]
queries={"common word 'lamp'":"q=lamp","3 words":"q=brass+desk+lamp","typo 'lampshde'":"q=lampshde","rare word 'teapot'":"q=teapot",
         "empty (all + facets)":"","vendor filter":"vendor=Nordwood","price range + sort":"minPrice=10&maxPrice=20&sort=price_asc",
         "word + vendor + price":"q=chair&vendor=Nordwood&maxPrice=100"}
def one(qs):
    t=time.perf_counter()
    with urllib.request.urlopen(urllib.request.Request("http://localhost:8080/storefront/v1/search?"+qs, headers={"X-Forwarded-Host":host})) as r:
        body=json.loads(r.read())
    return (time.perf_counter()-t)*1000, body["page"]["total"]
for name,qs in queries.items():
    one(qs)  # warm-up
    with ThreadPoolExecutor(8) as ex: res=list(ex.map(lambda _: one(qs), range(100)))
    ms=sorted(r[0] for r in res)
    print(f"{name:24} matches {res[0][1]:>7} | p50 {statistics.median(ms):6.1f} ms | p95 {ms[94]:6.1f} ms | max {ms[-1]:6.1f} ms")

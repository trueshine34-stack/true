import asyncio, os, ssl, sys, json
import edge_tts.communicate as C
C._SSL_CTX = ssl.create_default_context(cafile="/root/.ccr/ca-bundle.crt")
import edge_tts
async def main(voice, text, out, rate, pitch):
    c = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch, proxy=os.environ.get("HTTPS_PROXY"), boundary="WordBoundary")
    words = []
    with open(out, "wb") as f:
        async for ch in c.stream():
            if ch["type"] == "audio": f.write(ch["data"])
            elif ch["type"] == "WordBoundary":
                words.append({"t": ch["offset"] / 1e7, "d": ch["duration"] / 1e7, "w": ch["text"]})
    json.dump(words, open(out + ".json", "w"), ensure_ascii=False, indent=0)
    print(out, [(w["w"], round(w["t"], 2)) for w in words])
v, t, o, r, p = sys.argv[1:6]
asyncio.run(main(v, t, o, r, p))

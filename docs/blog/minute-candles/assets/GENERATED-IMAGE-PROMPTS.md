# 분봉 블로그 생성 이미지 프롬프트

- 생성일: 2026-08-11
- 생성 방식: Codex 내장 ImageGen
- 참고 가이드: `THUMBNAIL.md`, `DESIGN.md`
- 공통 규격: 16:9 가로형 PNG, 1672×941px
- 공통 목적: 어려운 개념을 쉽게 설명하는 삽화
- 제외 범위: 실제 실행 증거, 공급자 성능, 계약 승인, 운영 배포 상태

원본 이미지를 편집하지 않고 네 장 모두 새로 생성했습니다. 설명용 삽화에는 실제 측정 수치와 공급자 로고를 넣지 않았습니다.

## `thumbnail-minute-candles-final.png`

```text
Create a 16:9 landscape editorial thumbnail for a Korean developer blog article.

SUBJECT: Building a stock one-minute candlestick pipeline from individual trade ticks to a mobile chart.
COMPOSITION: Bright white, lightly textured notebook paper. In the center, a simple hand-drawn sequence flows left to right: several tiny trade marks and timestamps, a small analog one-minute clock, one clear red-and-blue candlestick, a compact database cylinder, and a smartphone with a simple candlestick chart. Use black/navy pen outlines with only muted blue (#2563EB), green (#10B981), and one small yellow highlight (#F59E0B). Slightly imperfect human pen strokes, subtle underlines and arrows, lots of breathing room. Keep all important content inside the central 80% with 8% safe margins.
TEXT: Include only this exact Korean title, large and legible in two lines maximum: "주식 1분봉 만들기". No subtitle, no extra labels, no pseudo-text.
STYLE: A developer's real notebook sketch / hand-drawn technical note, modest and educational, not glossy. Editorial diagram, not a UI mockup.
AVOID: futuristic dashboard, neon, gradients, glassmorphism, 3D, photorealism, rockets, stars, robots, glowing effects, heavy shadows, excessive cards or badges, corporate advertising, dense text, fake code, gibberish, logos, watermarks.
```

## `06-ticks-to-ohlcv.png`

```text
Create a 16:9 landscape scientific-educational illustration for a Korean developer blog.

CONCEPT: Several individual stock trades inside one minute are summarized into one OHLCV candlestick.
COMPOSITION: Bright off-white lightly textured notebook paper. On the left, draw six small trade points on a simple price-over-time sketch. Make the first point an open circle, the last point a filled circle, with one obvious highest point and one obvious lowest point. In the center, gather them with a loose hand-drawn brace or funnel into a small analog one-minute clock. On the right, draw one large accurate candlestick: the body visually spans the first and last trade prices, the upper and lower wicks reach the highest and lowest points, and a small stack of tally marks or blocks below represents the summed volume. Use simple arrows and generous whitespace. Keep all key content within the central 80% and 8% safe margins.
STYLE: A real developer's study notebook, slightly uneven black/navy ballpoint strokes, subtle blue (#2563EB) aggregation marks, one small green (#10B981) accent on the completed result. Clean enough to teach, visibly hand-drawn rather than vector-perfect.
TEXT: No words, no letters, no numbers, no timestamps, no labels, no equations, no pseudo-writing, no logos, no watermark.
AVOID: dashboards, multiple candlestick charts, moving-average lines, coins, currency symbols, company logos, 3D, gradients, neon, glass cards, glossy corporate infographic style, dense decoration, impossible axes, polished vector geometry.
```

## `07-rights-before-speed.png`

```text
Create a 16:9 landscape hand-drawn editorial illustration for a Korean developer blog.

CONCEPT: Before comparing market-data API speed, a developer must first check permission to store, display, and redistribute the data.
COMPOSITION: On bright white lightly textured notebook paper, show three small unlabeled API plugs or data pipes approaching from the left. In the center is a simple paper contract/checklist held like a gate, with a small keyhole and three clear visual permission symbols: a database cylinder, a computer screen, and several people. Only after this gate, on the right, place a small stopwatch and speed gauge. The visual reading order must unmistakably be "permission gate first, speed test second." Add a few hand-drawn arrows and one yellow highlighter stroke behind the contract. Keep all important content in the central 80% with 8% safe margins.
STYLE: Human developer notebook sketch, slightly uneven black/navy ink lines, restrained blue (#2563EB), green (#10B981), and a tiny yellow accent (#F59E0B), generous whitespace, simple and educational.
TEXT: No words, no letters, no numbers, no labels, no logos, no pseudo-text, no watermark.
AVOID: corporate infographic cards, futuristic dashboards, neon, gradients, glassmorphism, 3D, photorealistic hands, robots, rockets, glowing effects, stock exchange logos, brand marks, dense decorations, glossy advertising.
```

## `08-one-source-many-users.png`

```text
Create a 16:9 landscape hand-drawn educational illustration for a Korean developer blog.

CONCEPT: Do not open one market-data connection per user. A server receives one shared market feed, stores or caches it once, and fans the same chart data out to many users.
COMPOSITION: Bright off-white lightly textured notebook paper. On the far left, draw one simple unbranded market-feed cable emitting a few trade dots into one central server inlet. In the center, show a small database cylinder and a modest cache tray beside the server. From that shared center, draw five clean branching arrows to five different simple device outlines on the right (phones and browser screens), each showing the same tiny candlestick pattern. Under or beside the main design, show a faint crossed-out alternative: many separate cables running directly to users, but keep it small and secondary. The main message must read visually as "one collection path, many viewers." Keep all key objects within the central 80% with 8% safe margins.
STYLE: Real developer notebook sketch, slightly uneven black/navy ballpoint lines, restrained blue (#2563EB) for the single incoming/shared outgoing flow, minimal green (#10B981) for the cache or completed data. Generous whitespace, personal and explanatory rather than polished.
TEXT: No words, no letters, no numbers, no labels, no vendor or product logos, no pseudo-text, no watermark.
AVOID: cloud-provider architecture icons, futuristic networks, glowing lines, neon, gradients, glassmorphism, 3D, server racks, excessive users, dense spaghetti arrows, cards, badges, glossy corporate infographic style, advertising, rockets, robots.
```

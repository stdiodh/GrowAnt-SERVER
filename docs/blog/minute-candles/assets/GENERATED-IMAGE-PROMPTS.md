# 분봉 블로그 생성 이미지 프롬프트

- 생성일: 2026-08-11
- 생성 방식: 삽화·종이 배경은 Codex 내장 ImageGen, 결과표의 한글·수치·선은 HTML/Chrome 렌더링
- 참고 가이드: `THUMBNAIL.md`, `DESIGN.md`
- 삽화 규격: 16:9 가로형 PNG, 1672×941px
- 결과표 규격: 4:5 세로형 PNG, 1080×1350px
- 공통 목적: 어려운 개념을 쉽게 설명하는 삽화
- 제외 범위: 실제 실행 증거, 공급자 성능, 계약 승인, 운영 배포 상태

대표 이미지와 설명용 삽화 세 장은 ImageGen으로 생성했습니다. 최종 검수에서 썸네일의 캔들 색과 사용 권리 삽화의 승인 표현을 편집했고, OHLCV 삽화는 좌표 관계를 오해하지 않도록 초안을 폐기한 뒤 새로 생성했습니다. 설명용 삽화에는 실제 측정 수치와 공급자 로고를 넣지 않았습니다.

결과 요약 `02`~`05`는 같은 분위기를 유지하면서도 한글과 수치를 정확히 보존하기 위해 혼합 방식으로 만들었습니다. ImageGen은 글자가 없는 공통 종이 배경만 만들고, 표·수치·선·강조는 `source/result-sheets.html`과 `scripts/render-blog-result-sheets.cjs`로 결정적으로 렌더링합니다.

## `source/result-paper-background.png`

```text
Use case: productivity-visual
Asset type: reusable blank background for four Korean developer-blog benchmark result sheets

Create a completely blank 16:9 landscape notebook-paper background that matches a real developer's hand-drawn technical study note.

Scene/backdrop: bright warm off-white paper with very subtle natural fibers and faint pale-blue horizontal notebook ruling. Add only tiny imperfect navy pen corner marks, one short blue underline stroke near the upper left, and one very small yellow highlighter swipe near the lower right. The entire central 88% must remain clean and empty so exact tables and Korean text can be overlaid later.

Style/medium: scanned notebook paper, understated, human, slightly imperfect, calm and educational. White/off-white #F8FAFC, navy #0F172A, restrained blue #2563EB, tiny yellow #F59E0B.

Composition/framing: exact 16:9 landscape, generous 7% safe margin, flat front-facing paper, no perspective, no objects.

Text: no text, letters, numbers, symbols, labels, pseudo-writing, logos, or watermark.

Avoid: cards, boxes, dashboards, charts, icons, gradients, glassmorphism, neon, 3D, heavy shadows, glossy advertising, torn edges, desk props, hands, pens, clips, sticky notes, dense texture.
```

### 결과표 렌더링 방식

- 대상: `02-local-api-evidence.png`, `03-storage-aggregation.png`, `04-k6-load-test.png`, `05-gradle-integration-tests.png`
- 배경: 위 ImageGen 원본
- 정확한 글자·표: 로컬 HTML/CSS 렌더링
- 글꼴: Pretendard, 수치 보조 D2Coding
- 재현 명령: `node scripts/render-blog-result-sheets.cjs`
- 재현 조건: Chrome, Pretendard, D2Coding, `playwright-core`가 필요하며 다른 설치 경로는 `CHROME_BIN`, `PLAYWRIGHT_CORE_PATH`로 지정
- 원칙: 어두운 대시보드·카드·배지를 피하고, 가로 줄과 펜 선으로 결과를 구분합니다.
- 본문 적용: Velog 모바일 폭에서도 표를 읽을 수 있도록 4:5 세로형과 34px 이상의 본문 글자를 사용합니다.

## `thumbnail-minute-candles-final.png`

```text
Create a 16:9 landscape editorial thumbnail for a Korean developer blog article.

SUBJECT: Building a stock one-minute candlestick pipeline from individual trade ticks to a mobile chart.
COMPOSITION: Bright white, lightly textured notebook paper. In the center, a simple hand-drawn sequence flows left to right: several tiny trade marks and timestamps, a small analog one-minute clock, one clear red-and-blue candlestick, a compact database cylinder, and a smartphone with a simple candlestick chart. Use black/navy pen outlines with only muted blue (#2563EB), green (#10B981), and one small yellow highlight (#F59E0B). Slightly imperfect human pen strokes, subtle underlines and arrows, lots of breathing room. Keep all important content inside the central 80% with 8% safe margins.
TEXT: Include only this exact Korean title, large and legible in two lines maximum: "주식 1분봉 만들기". No subtitle, no extra labels, no pseudo-text.
STYLE: A developer's real notebook sketch / hand-drawn technical note, modest and educational, not glossy. Editorial diagram, not a UI mockup.
AVOID: futuristic dashboard, neon, gradients, glassmorphism, 3D, photorealism, rockets, stars, robots, glowing effects, heavy shadows, excessive cards or badges, corporate advertising, dense text, fake code, gibberish, logos, watermarks.
```

### 최종 교정 프롬프트

```text
Edit this existing 16:9 hand-drawn developer-blog thumbnail with one precise correction only.

KEEP: the exact Korean title "주식 1분봉 만들기", all lettering, paper texture, timestamps, clock, database, phone, arrows, layout, crop, margins, hand-drawn navy pen style, and all other objects unchanged.

CHANGE ONLY THE SINGLE LARGE CANDLE BETWEEN THE CLOCK AND DATABASE: it currently has one body split into red on top and blue on bottom, which is financially misleading. Replace it with one normal candlestick using a single solid muted blue fill for the entire rectangular body, with one continuous upper wick and one continuous lower wick in navy. Do not split the body, do not add another candle, and do not alter the small phone chart.

Do not add or remove any text. Preserve the exact 16:9 composition and title spelling.
```

## `06-ticks-to-ohlcv.png`

```text
Create a brand-new 16:9 landscape scientific-educational illustration for a Korean developer blog.

CONCEPT ONLY: Many individual stock trades recorded during one minute are gathered and summarized into one standard candlestick plus total volume. Do NOT attempt to map plotted point heights to candle edges; the accompanying table explains the exact OHLC calculation.

COMPOSITION: Bright off-white lightly textured notebook paper. On the left, draw six separate small trade-record tokens arranged loosely in time order. Each token is only a tiny outlined receipt shape containing one simple price dot and one small quantity tally—no words or numbers. Do not connect the tokens into a line graph. In the center, draw a clear hand-drawn funnel wrapped by a small analog one-minute clock, gathering all six tokens. On the right, draw one normal standard candlestick with a single solid muted blue rectangular body and one continuous upper wick and lower wick, plus a separate neat row of tally marks underneath for total volume. Use simple left-to-right arrows and generous whitespace. Keep all important content inside the central 80% with 8% safe margins.

STYLE: A real developer's study notebook, slightly uneven black/navy ballpoint strokes, restrained blue (#2563EB), and a tiny green (#10B981) accent on the completed grouping. Clean but visibly hand drawn, not vector-perfect.

ACCURACY: exactly one candlestick; its body must be a single color, never split; volume must be visibly separate below it; no moving-average line; no connected price line; no check mark or approval symbol.

TEXT: No words, letters, numbers, timestamps, labels, equations, logos, pseudo-writing, or watermark.

AVOID: dashboards, multiple charts, coins, currency symbols, company logos, 3D, gradients, neon, glass cards, corporate infographic styling, dense decoration, fake axes, and any visual claim that token heights directly align with candle edges.
```

### 최종 교정 프롬프트

```text
Edit this existing 16:9 hand-drawn trade-tokens-to-candlestick illustration with one precise correction to avoid a false arithmetic claim.

KEEP: off-white notebook paper, six receipt-like trade tokens on the left, their price dots, central funnel and one-minute clock, arrows, single solid blue candlestick with upper/lower wick, hand-drawn navy/blue style, layout, crop, margins, and generous whitespace.

CHANGE THE VOLUME SYMBOLS ONLY:
- Inside every trade token, remove all countable tally marks.
- Replace those tally marks with one simple solid muted-blue horizontal quantity bar. Let the six bars have visibly different lengths, but none may contain segments, ticks, numbers, or countable units.
- Under the completed candlestick, remove all countable tally marks.
- Replace them with one single long muted-blue horizontal volume bar, with the existing small green brace underneath if helpful.
- The result should communicate "different trade quantities are combined into total volume" without allowing the viewer to count units or infer a numeric sum.

Do not add text, letters, numbers, labels, check marks, logos, or watermarks. Do not change the candle or any other object. Preserve exact 16:9 composition and the developer-notebook style.
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

### 최종 교정 프롬프트

```text
Edit this existing 16:9 hand-drawn market-data permission illustration with one semantic correction.

KEEP: the white ruled notebook paper, three unbranded input plugs on the left, the central document gate, database/screen/people icons, yellow highlighter, keyhole, arrow to stopwatch and speed gauge, hand-drawn navy/blue style, crop, spacing, and all other objects.

CHANGE: remove every green approval check mark from the document. Replace each of the three check marks with an EMPTY outlined checkbox or empty outlined circle in dark navy. Add one small hand-drawn magnifying glass resting beside the document to make the meaning "questions that must be investigated," not "permissions already approved." The gate must remain visually closed/conditional and must not look like an official license certificate.

Do not add words, letters, numbers, logos, seals, signatures, approval badges, or watermarks. Keep the exact 16:9 composition and the same simple developer-notebook style.
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

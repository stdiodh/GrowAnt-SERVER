const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const repoRoot = path.resolve(__dirname, "..");
const htmlPath = path.join(
  repoRoot,
  "docs/blog/minute-candles/assets/source/blog-diagrams.html",
);
const outputDir = path.join(repoRoot, "docs/blog/minute-candles/assets");

const playwrightCandidates = [
  process.env.PLAYWRIGHT_CORE_PATH,
  "playwright-core",
  path.resolve(
    path.dirname(process.execPath),
    "../lib/node_modules/openclaw/node_modules/playwright-core",
  ),
].filter(Boolean);

let chromium;
for (const candidate of playwrightCandidates) {
  try {
    ({ chromium } = require(candidate));
    break;
  } catch {
    // 다음 설치 경로를 확인합니다.
  }
}

if (!chromium) {
  throw new Error("playwright-core를 찾지 못했습니다. PLAYWRIGHT_CORE_PATH를 지정해 주세요.");
}

const chromePath = process.env.CHROME_BIN ||
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
if (!fs.existsSync(chromePath)) {
  throw new Error("Chrome을 찾지 못했습니다. CHROME_BIN을 지정해 주세요.");
}

const diagrams = {
  aggregation: {
    filename: "10-ohlcv-aggregation-flow.png",
    expectedNodes: 9,
    expectedLines: 8,
    expectedText: ["09:00:05", "70,500원", "5주", "10 + 5 + 8 = 23주"],
  },
  pipeline: {
    filename: "11-minute-candle-data-pipeline.png",
    expectedNodes: 10,
    expectedLines: 9,
    expectedText: ["로컬 체결", "pending", "공급자 REST", "누락 복구·대조"],
  },
  watermark: {
    filename: "12-watermark-timeline.png",
    expectedNodes: 4,
    expectedLines: 4,
    expectedText: ["09:01:00", "09:01:05 이후", "간격 비례 아님"],
  },
  retry: {
    filename: "13-pending-retry-flow.png",
    expectedNodes: 5,
    expectedLines: 5,
    expectedText: ["프로세스 메모리", "pending에서 제거", "다음 주기에 다시 시도"],
  },
};

async function main() {
  const browser = await chromium.launch({
    executablePath: chromePath,
    headless: true,
  });

  try {
    const layoutErrors = [];
    for (const [diagram, expectation] of Object.entries(diagrams)) {
      const { filename, expectedNodes, expectedLines, expectedText } = expectation;
      const outputPath = path.join(outputDir, filename);
      const pageUrl = `${pathToFileURL(htmlPath).href}?diagram=${diagram}`;
      const page = await browser.newPage({
        viewport: { width: 1080, height: 1350 },
        deviceScaleFactor: 1,
      });
      await page.goto(pageUrl, { waitUntil: "load" });
      await page.evaluate(() => document.fonts.ready);

      const layout = await page.evaluate(() => {
        const sheet = document.querySelector(".sheet.active");
        const safeX = 1080 * 0.07;
        const safeY = 1350 * 0.07;
        const textRuns = [];
        const walker = document.createTreeWalker(sheet, NodeFilter.SHOW_TEXT);
        let textNode = walker.nextNode();
        while (textNode) {
          const value = textNode.textContent.trim();
          if (value) {
            const range = document.createRange();
            range.selectNodeContents(textNode);
            textRuns.push({
              value,
              size: Number.parseFloat(getComputedStyle(textNode.parentElement).fontSize),
              rects: [...range.getClientRects()]
                .filter((rect) => rect.width > 0 && rect.height > 0)
                .map((rect) => ({
                  left: rect.left,
                  right: rect.right,
                  top: rect.top,
                  bottom: rect.bottom,
                })),
            });
          }
          textNode = walker.nextNode();
        }

        return {
          clientWidth: sheet.clientWidth,
          scrollWidth: sheet.scrollWidth,
          clientHeight: sheet.clientHeight,
          scrollHeight: sheet.scrollHeight,
          undersizedText: textRuns
            .filter(({ size }) => size < 30)
            .map(({ value, size }) => ({ value, size })),
          unreadableMobileText: textRuns
            .filter(({ size }) => size * 390 / 1080 < 10)
            .map(({ value, size }) => ({
              value,
              renderedAt390: Number((size * 390 / 1080).toFixed(1)),
            })),
          unsafeText: textRuns.flatMap(({ value, rects }) => rects
            .filter((rect) => (
              rect.left < safeX ||
              rect.right > 1080 - safeX ||
              rect.top < safeY ||
              rect.bottom > 1350 - safeY
            ))
            .map((rect) => ({ value, rect }))),
          nodeCount: sheet.querySelectorAll(".node").length,
          lineCount: sheet.querySelectorAll(".connections .line").length,
          allText: sheet.textContent,
        };
      });

      if (layout.scrollWidth > layout.clientWidth || layout.scrollHeight > layout.clientHeight) {
        layoutErrors.push(`${filename} 내용이 캔버스 영역을 넘습니다: ${JSON.stringify(layout)}`);
      }
      if (layout.undersizedText.length > 0) {
        layoutErrors.push(`${filename}에 30px 미만 글자가 있습니다: ${JSON.stringify(layout.undersizedText)}`);
      }
      if (layout.unreadableMobileText.length > 0) {
        layoutErrors.push(
          `${filename}의 390px 환산 글자가 10px 미만입니다: ${JSON.stringify(layout.unreadableMobileText)}`,
        );
      }
      if (layout.unsafeText.length > 0) {
        layoutErrors.push(`${filename}에 7% 안전 여백 밖 글자가 있습니다: ${JSON.stringify(layout.unsafeText)}`);
      }
      if (layout.nodeCount !== expectedNodes || layout.lineCount !== expectedLines) {
        layoutErrors.push(
          `${filename} 노드/연결선 수가 예상과 다릅니다: ${layout.nodeCount}/${layout.lineCount}`,
        );
      }
      const missingText = expectedText.filter((value) => !layout.allText.includes(value));
      if (missingText.length > 0) {
        layoutErrors.push(`${filename} 필수 문구가 없습니다: ${missingText.join(", ")}`);
      }

      await page.screenshot({
        path: outputPath,
        type: "png",
        fullPage: false,
        animations: "disabled",
      });
      await page.close();

      const png = fs.readFileSync(outputPath);
      const width = png.readUInt32BE(16);
      const height = png.readUInt32BE(20);
      if (width !== 1080 || height !== 1350) {
        throw new Error(`${filename} 크기가 ${width}x${height}입니다.`);
      }
      process.stdout.write(`${filename}: ${width}x${height}\n`);
    }

    if (layoutErrors.length > 0) {
      throw new Error(layoutErrors.join("\n"));
    }
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});

const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");

const repoRoot = path.resolve(__dirname, "..");
const htmlPath = path.join(
  repoRoot,
  "docs/blog/minute-candles/assets/source/result-sheets.html",
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

const sheets = {
  api: "02-local-api-evidence.png",
  storage: "03-storage-aggregation.png",
  load: "04-k6-load-test.png",
  tests: "05-gradle-integration-tests.png",
};

async function main() {
  const browser = await chromium.launch({
    executablePath: chromePath,
    headless: true,
  });

  try {
    const layoutErrors = [];
    for (const [sheet, filename] of Object.entries(sheets)) {
      const outputPath = path.join(outputDir, filename);
      const pageUrl = `${pathToFileURL(htmlPath).href}?sheet=${sheet}`;
      const page = await browser.newPage({
        viewport: { width: 1080, height: 1350 },
        deviceScaleFactor: 1,
      });
      await page.goto(pageUrl, { waitUntil: "load" });
      await page.evaluate(() => document.fonts.ready);
      const layout = await page.evaluate(() => {
        const content = document.querySelector(".content");
        const safeX = 1080 * 0.07;
        const safeY = 1350 * 0.07;
        const textRuns = [];
        const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
        let textNode = walker.nextNode();
        while (textNode) {
          const text = textNode.textContent.trim();
          if (text) {
            const range = document.createRange();
            range.selectNodeContents(textNode);
            textRuns.push({
              text,
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

        const undersizedText = textRuns
          .filter(({ size }) => size < 34)
          .map(({ text, size }) => ({ text, size }));
        const unsafeText = textRuns.flatMap(({ text, rects }) => rects
          .filter((rect) => (
            rect.left < safeX ||
            rect.right > 1080 - safeX ||
            rect.top < safeY ||
            rect.bottom > 1350 - safeY
          ))
          .map((rect) => ({
            text,
            rect: {
              left: Math.round(rect.left),
              right: Math.round(rect.right),
              top: Math.round(rect.top),
              bottom: Math.round(rect.bottom),
            },
          })));

        return {
          clientWidth: content.clientWidth,
          scrollWidth: content.scrollWidth,
          clientHeight: content.clientHeight,
          scrollHeight: content.scrollHeight,
          childMetrics: [...content.children].map((element) => ({
            tag: element.tagName.toLowerCase(),
            className: element.className,
            height: Math.round(element.getBoundingClientRect().height),
            marginTop: Number.parseFloat(getComputedStyle(element).marginTop),
            marginBottom: Number.parseFloat(getComputedStyle(element).marginBottom),
          })),
          undersizedText,
          unsafeText,
        };
      });
      if (layout.scrollHeight > layout.clientHeight) {
        layoutErrors.push(
          `${filename} 내용 높이가 ${layout.scrollHeight}px로 ${layout.clientHeight}px 영역을 넘습니다. ${JSON.stringify(layout.childMetrics)}`,
        );
      }
      if (layout.scrollWidth > layout.clientWidth + 1) {
        layoutErrors.push(
          `${filename} 내용 너비가 ${layout.scrollWidth}px로 ${layout.clientWidth + 1}px 허용 영역을 넘습니다.`,
        );
      }
      if (layout.undersizedText.length > 0) {
        layoutErrors.push(
          `${filename}에 34px 미만 글자가 있습니다: ${JSON.stringify(layout.undersizedText)}`,
        );
      }
      if (layout.unsafeText.length > 0) {
        layoutErrors.push(
          `${filename}에 7% 안전 여백 밖 글자가 있습니다: ${JSON.stringify(layout.unsafeText)}`,
        );
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

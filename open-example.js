const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();
  await page.goto('https://example.com');
  console.log('Opened https://example.com');
  // Keep browser open for a few seconds so you can see it
  await page.waitForTimeout(5000);
  await browser.close();
})();

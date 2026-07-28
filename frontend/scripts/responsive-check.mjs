// Headless responsive check for the GitGate dashboard.
//
// Logs in, visits every view at several widths, asserts there is NO horizontal
// page overflow (scrollWidth <= clientWidth), and writes a screenshot per
// view/width to ./responsive-shots. Exits non-zero if any view overflows, so it
// can gate a build.
//
//   npm run check:responsive                 # against http://localhost:8080
//   RESP_BASE=http://localhost:5173 npm run check:responsive
//
// Env: RESP_BASE (url), RESP_USER, RESP_PASS, RESP_WIDTHS ("320,375,768"),
//      CHROME (path to chrome/edge if auto-detect fails).
import puppeteer from 'puppeteer-core'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = (process.env.RESP_BASE || 'http://localhost:8080').replace(/\/$/, '') + '/'
const USER = process.env.RESP_USER || 'admin'
const PASS = process.env.RESP_PASS || 'admin123'
const WIDTHS = (process.env.RESP_WIDTHS || '320,375,768,1024,1280').split(',').map((w) => parseInt(w.trim(), 10))

const here = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(here, '..', 'responsive-shots')

function findChrome() {
  if (process.env.CHROME && existsSync(process.env.CHROME)) return process.env.CHROME
  const candidates = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe`,
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ]
  const hit = candidates.find((p) => p && existsSync(p))
  if (!hit) throw new Error('Chrome/Edge not found. Set CHROME=<path-to-browser>.')
  return hit
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function clickText(page, sel, text) {
  const ok = await page.evaluate((s, t) => {
    const el = [...document.querySelectorAll(s)].find((e) => e.textContent.trim().includes(t))
    if (el) { el.click(); return true }
    return false
  }, sel, text)
  if (!ok) throw new Error(`no "${sel}" containing "${text}"`)
  await sleep(500)
}

async function overflow(page) {
  return page.evaluate(() => ({
    sw: document.documentElement.scrollWidth,
    cw: document.documentElement.clientWidth,
  }))
}

const failures = []

async function check(page, w, name, fn) {
  if (fn) await fn()
  await sleep(400)
  const { sw, cw } = await overflow(page)
  const bad = sw > cw + 1
  await page.screenshot({ path: `${OUT}/${name}-${w}.png`, fullPage: true })
  const tag = bad ? 'OVERFLOW' : 'ok'
  console.log(`  [${tag}] ${name}-${w}  (scrollW=${sw} clientW=${cw})`)
  if (bad) failures.push(`${name}@${w} (scrollW=${sw} > clientW=${cw})`)
}

const browser = await puppeteer.launch({ executablePath: findChrome(), headless: 'new', args: ['--no-sandbox'] })
const page = await browser.newPage()
page.setDefaultTimeout(15000)

try {
  rmSync(OUT, { recursive: true, force: true })
  mkdirSync(OUT, { recursive: true })

  for (const w of WIDTHS) {
    console.log(`\n=== ${w}px ===`)
    await page.setViewport({ width: w, height: 900, deviceScaleFactor: 1 })

    // logged-out auth screen
    await page.goto(BASE, { waitUntil: 'networkidle2' })
    await page.evaluate(() => localStorage.clear())
    await page.reload({ waitUntil: 'networkidle2' })
    await page.waitForSelector('#u')
    await check(page, w, 'auth')

    // log in
    await page.type('#u', USER)
    await page.type('#p', PASS)
    await clickText(page, 'button[type=submit]', 'Sign in')
    await page.waitForFunction(() => !document.querySelector('#u'))
    await page.waitForSelector('.AppHeader')
    await sleep(700)
    await check(page, w, 'repos')

    // header menu open (mobile) / inline (desktop)
    const hasToggle = await page.evaluate(() => {
      const t = document.querySelector('.hdr-toggle')
      return t && getComputedStyle(t).display !== 'none'
    })
    if (hasToggle) {
      await page.click('.hdr-toggle')
      await check(page, w, 'nav-open')
      await page.click('.hdr-toggle')
      await sleep(300)
    }

    // open first repo -> code
    await clickText(page, '.reponame', '')
    await page.waitForSelector('.UnderlineNav')
    await sleep(600)
    await check(page, w, 'repo-code')

    // settings (visibility + people + danger zone), if present
    const hasSettings = await page.evaluate(() =>
      [...document.querySelectorAll('.UnderlineNav-item')].some((e) => e.textContent.includes('Settings')))
    if (hasSettings) {
      await clickText(page, '.UnderlineNav-item', 'Settings')
      await page.waitForSelector('.Box-danger')
      await check(page, w, 'repo-settings')

      // The path picker is the densest thing in the UI — a file tree, a per-row access
      // control and a chip list. Check it OPEN, not just the collapsed settings page.
      await clickText(page, '.btn', 'Add people')
      await page.waitForSelector('.picker')
      await sleep(700) // tree loads over the network
      await check(page, w, 'settings-add-person')

      // Expand a folder: deeper indentation is where a tree starts pushing rows wide.
      const expanded = await page.evaluate(() => {
        const t = document.querySelector('.picker-twisty[aria-expanded="false"]')
        if (!t) return false
        t.click(); return true
      })
      if (expanded) {
        await sleep(700)
        // Grant something so the chip summary renders too.
        await page.evaluate(() => {
          const seg = [...document.querySelectorAll('.segmented-item')].find((b) => b.textContent.trim() === 'Write')
          if (seg) seg.click()
        })
        await check(page, w, 'settings-tree-expanded')
      }
      await clickText(page, '.btn', 'Cancel')

      // Public-paths picker (read-only mode) under Visibility.
      const wentPublic = await page.evaluate(() => {
        const card = [...document.querySelectorAll('.choice-card')].find((c) => c.textContent.includes('Public'))
        if (!card) return false
        card.click(); return true
      })
      if (wentPublic) {
        await page.waitForSelector('.picker-toggle')
        await sleep(700)
        await check(page, w, 'settings-public-paths')
        // Leave the repo as we found it.
        await page.evaluate(() => {
          const card = [...document.querySelectorAll('.choice-card')].find((c) => c.textContent.includes('Private'))
          if (card) card.click()
        })
      }
    }

    // back -> Explore
    await clickText(page, '.btn', '← Repositories')
    await sleep(300)
    // header may be collapsed; open it first
    const t2 = await page.evaluate(() => {
      const t = document.querySelector('.hdr-toggle')
      return t && getComputedStyle(t).display !== 'none'
    })
    if (t2) { await page.click('.hdr-toggle'); await sleep(300) }
    await clickText(page, '.AppHeader .btn', 'Explore')
    await page.waitForSelector('.search-input')
    await check(page, w, 'explore')

    // Admin (superadmin only)
    const t3 = await page.evaluate(() => {
      const t = document.querySelector('.hdr-toggle')
      return t && getComputedStyle(t).display !== 'none'
    })
    if (t3) { await page.click('.hdr-toggle'); await sleep(300) }
    const hasAdmin = await page.evaluate(() =>
      [...document.querySelectorAll('.AppHeader .btn')].some((e) => e.textContent.trim() === 'Admin'))
    if (hasAdmin) {
      await clickText(page, '.AppHeader .btn', 'Admin')
      await page.waitForSelector('table.list')
      await check(page, w, 'admin')
    }
  }
} finally {
  await browser.close()
}

console.log(`\nScreenshots written to ${OUT}`)
if (failures.length) {
  console.error(`\nFAILED — horizontal overflow in ${failures.length} view(s):`)
  failures.forEach((f) => console.error('  - ' + f))
  process.exit(1)
}
console.log('\nPASS — no horizontal overflow at any width.')

/**
 * Implementation of the phone mirror: the device pipeline, the multipart
 * stream, and the small control API the right-sidebar panel uses.
 *
 * `adb screenrecord` encodes the display into raw H.264, ffmpeg decodes it and
 * re-encodes JPEG frames, and the panel shows the multipart response in an
 * `<img>`. The pipeline runs only while a browser holds the stream open, so a
 * closed panel costs nothing.
 *
 * Turning the panel off is delegated to a short-lived scrcpy "holder": scrcpy
 * uses SurfaceControl, which keeps display composition alive. `cmd display
 * power-off` switches DisplayManager off instead and stops the compositor, so
 * screenrecord would freeze and screencap would return black.
 */
import { spawn } from 'node:child_process'
import { accessSync, constants, readdirSync } from 'node:fs'
import { delimiter, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/** Tuning of the mirror; change a value here and toggle the plugin. */
const MIRROR = {
  /** Device-side encode size: half of a 1080x2400 panel stays readable in a ~400px pane. */
  size: '540x1200',
  bitrate: '6M',
  /**
   * Frames per second handed to the browser. The device encodes far more while
   * the screen moves (it follows the display refresh rate), so this is the
   * smoothness knob: the pipeline already decodes them, dropping them is free.
   */
  fps: 30,
  /** ffmpeg `-q:v`, 2 (best) .. 31 (worst). */
  quality: 5,
  /** Keep the encoder warm this long after the last viewer leaves. */
  idleMs: 15000,
}

const STREAM_PATH = '/phone-mirror/stream.mjpg'
const SNAPSHOT_PATH = '/phone-mirror/snapshot.jpg'
const STATUS_PATH = '/phone-mirror/status.json'
const SCREEN_PATH = '/phone-mirror/screen'

/** This plugin's directory, used to find the project's scrcpy build. */
const PLUGIN_DIR = dirname(fileURLToPath(import.meta.url))

/** @returns whether `path` is an executable file. */
function isExecutable(path) {
  try {
    accessSync(path, constants.X_OK)
    return true
  } catch {
    return false
  }
}

/**
 * Locate the scrcpy binary without asking the operator to configure anything:
 * an explicit override, then PATH, then the release downloaded under the
 * project's `outputs/tools/scrcpy-*`.
 * @returns the executable path, or null when scrcpy is not available.
 */
function findScrcpy() {
  const override = process.env.PHONE_MIRROR_SCRCPY
  if (override) return isExecutable(override) ? override : null
  for (const dir of (process.env.PATH ?? '').split(delimiter)) {
    if (dir === '') continue
    const candidate = join(dir, 'scrcpy')
    if (isExecutable(candidate)) return candidate
  }
  const tools = join(PLUGIN_DIR, '..', '..', 'outputs', 'tools')
  try {
    for (const entry of readdirSync(tools)) {
      if (!entry.startsWith('scrcpy-')) continue
      const candidate = join(tools, entry, 'scrcpy')
      if (isExecutable(candidate)) return candidate
    }
  } catch {}
  return null
}

/** The default display, as `cmd display` addresses it. */
const DISPLAY_ID = '0'
/** KEYCODE_WAKEUP, the fallback when a display cannot be powered back on. */
const KEY_WAKEUP = '224'
const BOUNDARY = 'frame'
const SOI = Buffer.from([0xff, 0xd8, 0xff])
const EOI = Buffer.from([0xff, 0xd9])
/** A frame this large means the parser lost sync; drop the carry instead of growing it. */
const CARRY_LIMIT = 4 * 1024 * 1024
/** Retry delays after consecutive pipeline failures; the last one repeats. */
const RETRY_STEPS = [400, 800, 1500, 2500, 4000, 5000]
/** What `screenrecord` prints when the display is off; it cannot record a sleeping display. */
const DISPLAY_ASLEEP = /UNASSIGNED_LAYER_STACK|display state/i

/**
 * Whether a chunk is the recorder's own diagnostic text rather than H.264.
 * @param chunk - one stdout chunk.
 * @returns true when every byte is printable text.
 */
function looksLikeText(chunk) {
  for (const byte of chunk) {
    if (byte === 9 || byte === 10 || byte === 13) continue
    if (byte < 32 || byte > 126) return false
  }
  return true
}

/**
 * Run one short command and collect its output.
 * @param command - the executable.
 * @param args - its arguments.
 * @returns the exit outcome and the combined output tail.
 */
function run(command, args) {
  return new Promise((resolve) => {
    let output = ''
    let settled = false
    const done = (value) => {
      if (settled) return
      settled = true
      resolve(value)
    }
    let child
    try {
      child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    } catch (error) {
      done({ ok: false, detail: error.message })
      return
    }
    child.stdout.on('data', (chunk) => { output += chunk })
    child.stderr.on('data', (chunk) => { output += chunk })
    child.on('error', (error) => done({ ok: false, detail: error.message }))
    child.on('close', (code) => done({ ok: code === 0, detail: output.trim().slice(0, 300) }))
    const timer = setTimeout(() => {
      try {
        child.kill('SIGKILL')
      } catch {}
      done({ ok: false, detail: `${command} did not answer` })
    }, 8000)
    timer.unref?.()
  })
}

/**
 * Take one still of the display right now.
 *
 * `screenrecord` only encodes when the display content changes, so a panel
 * opened on a static screen would stay blank until something moved: this gives
 * every open an immediate picture, and the live stream takes over from there.
 * @returns one JPEG frame, or null when the device could not supply one.
 */
function stillFrame() {
  return new Promise((resolve) => {
    const shot = spawn('adb', ['exec-out', 'screencap', '-p'], { stdio: ['ignore', 'pipe', 'ignore'] })
    const png = []
    shot.stdout.on('data', (chunk) => png.push(chunk))
    shot.on('error', () => resolve(null))
    shot.on('close', (code) => {
      if (code !== 0 || png.length === 0) {
        resolve(null)
        return
      }
      const convert = spawn(
        'ffmpeg',
        [
          '-hide_banner', '-loglevel', 'error',
          '-f', 'png_pipe', '-i', 'pipe:0',
          '-frames:v', '1',
          '-f', 'image2pipe', '-c:v', 'mjpeg', '-q:v', String(MIRROR.quality),
          '-',
        ],
        { stdio: ['pipe', 'pipe', 'ignore'] },
      )
      const out = []
      convert.stdout.on('data', (chunk) => out.push(chunk))
      convert.on('error', () => resolve(null))
      convert.on('close', () => {
        const data = Buffer.concat(out)
        const start = data.indexOf(SOI)
        const end = start < 0 ? -1 : data.indexOf(EOI, start + 3)
        resolve(start >= 0 && end > start ? data.subarray(start, end + 2) : null)
      })
      convert.stdin.on('error', () => {})
      convert.stdin.end(Buffer.concat(png))
    })
  })
}

/** One browser holding the multipart response open. */
class Viewer {
  /**
   * @param res - the streaming HTTP response.
   * @param onGone - notified once when the browser drops the connection.
   */
  constructor(res, onGone) {
    this.res = res
    this.onGone = onGone
    this.blocked = false
    this.closed = false
    res.socket?.setNoDelay(true)
    res.on('drain', () => { this.blocked = false })
    res.on('close', () => this.gone())
    res.on('error', () => this.gone())
  }

  /** Settle the viewer once, whatever ended the response. */
  gone() {
    if (this.closed) return
    this.closed = true
    this.onGone(this)
  }

  /**
   * Write one frame unless this viewer is still draining the previous one,
   * which is what keeps a slow reader from growing the process buffer.
   * @param frame - one complete JPEG.
   */
  send(frame) {
    if (this.closed || this.blocked) return
    const head = Buffer.from(
      `--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`,
    )
    const tail = Buffer.from('\r\n')
    if (!this.res.write(Buffer.concat([head, frame, tail]))) this.blocked = true
  }
}

/** The device pipeline plus the viewers it serves. */
class PhoneMirror {
  constructor() {
    this.viewers = new Set()
    this.recorder = null
    this.encoder = null
    this.carry = Buffer.alloc(0)
    this.lastFrame = null
    this.lastFrameAt = 0
    this.nextSendAt = 0
    this.minGap = 1000 / MIRROR.fps
    this.detail = ''
    this.awake = true
    this.stdoutSeen = 0
    this.stderrTail = ''
    this.idleTimer = null
    this.restartTimer = null
    this.failures = 0
    this.suppressUntil = 0
    this.probedAt = 0
    this.holder = null
    this.holderDetail = ''
    this.stillPending = false
    this.disposed = false
  }

  /** Add one browser to the stream, starting the pipeline when it is the first. */
  attach(res) {
    res.writeHead(200, {
      'content-type': `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
      'cache-control': 'no-store, no-cache, must-revalidate, max-age=0',
      pragma: 'no-cache',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    res.flushHeaders?.()
    const viewer = new Viewer(res, (gone) => this.detach(gone))
    this.viewers.add(viewer)
    this.clearIdle()
    if (this.restartTimer !== null) {
      clearTimeout(this.restartTimer)
      this.restartTimer = null
    }
    this.start()
    if (this.lastFrame !== null && Date.now() - this.lastFrameAt < 1200) {
      viewer.send(this.lastFrame)
      return
    }
    void this.refreshStill()
  }

  /** Put one freshly taken still into the stream while the encoder catches up. */
  async refreshStill() {
    if (this.stillPending || this.disposed) return
    this.stillPending = true
    const frame = await stillFrame().catch(() => null)
    this.stillPending = false
    if (frame === null || this.disposed) return
    if (this.viewers.size === 0) return
    this.publish(frame)
  }

  /** Forget one browser, stopping the pipeline once the last one leaves. */
  detach(viewer) {
    if (!this.viewers.delete(viewer)) return
    if (this.viewers.size === 0) this.scheduleIdleStop()
  }

  /** Start `adb screenrecord` and ffmpeg unless they already run. */
  start() {
    if (this.disposed || this.recorder !== null || this.encoder !== null) return
    this.stderrTail = ''
    this.stdoutSeen = 0
    this.carry = Buffer.alloc(0)

    const recorder = spawn(
      'adb',
      [
        'exec-out', 'screenrecord',
        '--output-format=h264',
        '--bit-rate', MIRROR.bitrate,
        '--size', MIRROR.size,
        '--time-limit', '0',
        '-',
      ],
      { stdio: ['ignore', 'pipe', 'pipe'] },
    )
    const encoder = spawn(
      'ffmpeg',
      [
        '-hide_banner', '-loglevel', 'error',
        '-f', 'h264', '-i', 'pipe:0',
        '-an',
        '-f', 'image2pipe', '-c:v', 'mjpeg', '-q:v', String(MIRROR.quality),
        '-',
      ],
      { stdio: ['pipe', 'pipe', 'pipe'] },
    )
    this.recorder = recorder
    this.encoder = encoder

    const failed = (name, error) => this.fail(`${name}: ${error.message}`)
    recorder.on('error', (error) => failed('adb', error))
    encoder.on('error', (error) => failed('ffmpeg', error))
    recorder.on('exit', () => this.childExited())
    encoder.on('exit', () => this.childExited())
    for (const stream of [recorder.stdout, recorder.stderr, encoder.stdin, encoder.stdout, encoder.stderr]) {
      stream.on('error', () => {})
    }
    recorder.stdout.pipe(encoder.stdin)
    recorder.stdout.on('data', (chunk) => this.inspectRecorderOutput(chunk))
    recorder.stderr.on('data', (chunk) => this.note(chunk))
    encoder.stderr.on('data', (chunk) => this.note(chunk))
    encoder.stdout.on('data', (chunk) => this.ingest(chunk))
  }

  /**
   * Keep the recorder's first diagnostic, which is how a sleeping display is
   * reported: `screenrecord` writes it to the stream it was asked to record to.
   * @param chunk - one stdout chunk from `adb`.
   */
  inspectRecorderOutput(chunk) {
    if (this.lastFrame !== null || this.stdoutSeen > 1024) return
    this.stdoutSeen += chunk.length
    if (!looksLikeText(chunk)) return
    const text = chunk.toString('utf8').trim()
    if (text === '') return
    this.detail = text.split('\n')[0].slice(0, 300)
    if (DISPLAY_ASLEEP.test(this.detail)) this.awake = false
  }

  /** Record the tail of a pipeline diagnostic for the status endpoint. */
  note(chunk) {
    this.stderrTail = (this.stderrTail + chunk.toString()).slice(-600)
  }

  /** Report a pipeline error and get ready to try again. */
  fail(message) {
    this.detail = message.trim() || this.detail
    this.killChildren()
    this.scheduleRestart()
  }

  /** A pipeline child ended on its own: stop the other and retry while someone watches. */
  childExited() {
    if (this.disposed) return
    if (this.recorder === null && this.encoder === null) return
    if (this.detail === '' && this.stderrTail.trim() !== '') {
      this.detail = this.stderrTail.trim().split('\n').slice(-2).join(' ').slice(0, 300)
    }
    if (DISPLAY_ASLEEP.test(this.detail)) this.awake = false
    this.killChildren()
    this.scheduleRestart()
  }

  /** Terminate both children, then escalate stragglers. */
  killChildren() {
    const children = [this.recorder, this.encoder]
    this.recorder = null
    this.encoder = null
    for (const child of children) {
      if (child === null || child.exitCode !== null || child.signalCode !== null) continue
      try {
        child.kill('SIGTERM')
      } catch {}
      const timer = setTimeout(() => {
        if (child.exitCode !== null || child.signalCode !== null) return
        try {
          child.kill('SIGKILL')
        } catch {}
      }, 1000)
      timer.unref?.()
    }
  }

  /**
   * Retry the pipeline while a viewer still waits, backing off so a phone whose
   * screen is off (where `screenrecord` cannot run) is probed gently instead of
   * continuously.
   */
  scheduleRestart() {
    if (this.disposed || this.restartTimer !== null || this.viewers.size === 0) return
    const step = RETRY_STEPS[Math.min(this.failures, RETRY_STEPS.length - 1)]
    this.failures += 1
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null
      if (this.viewers.size === 0) return
      this.start()
    }, step)
    this.restartTimer.unref?.()
  }

  /** Split the encoder's byte stream into JPEG frames and publish the ones due now. */
  ingest(chunk) {
    this.failures = 0
    this.carry = this.carry.length === 0 ? chunk : Buffer.concat([this.carry, chunk])
    if (this.carry.length > CARRY_LIMIT) {
      this.carry = Buffer.alloc(0)
      return
    }
    for (;;) {
      const start = this.carry.indexOf(SOI)
      if (start < 0) {
        this.carry = this.carry.subarray(Math.max(0, this.carry.length - 2))
        return
      }
      const end = this.carry.indexOf(EOI, start + 3)
      if (end < 0) {
        if (start > 0) this.carry = this.carry.subarray(start)
        return
      }
      const frame = this.carry.subarray(start, end + 2)
      this.carry = this.carry.subarray(end + 2)
      this.publish(frame)
    }
  }

  /** Keep the newest frame, then hand every viewer at most `fps` frames per second. */
  publish(frame) {
    const now = Date.now()
    // Frames already in flight when the display was told to sleep are stale:
    // dropping them keeps `awake` from flipping straight back to true.
    if (now < this.suppressUntil) return
    this.lastFrame = frame
    this.lastFrameAt = now
    this.detail = ''
    if (this.viewers.size === 0) return
    if (this.lastFrameAt < this.nextSendAt) return
    this.nextSendAt = this.lastFrameAt + this.minGap
    for (const viewer of this.viewers) viewer.send(frame)
  }

  /**
   * Ask the device whether its display is awake. The pipeline only notices a
   * sleeping display when it restarts, so the button's label needs this probe as
   * well: the screen can time out on its own while frames simply stop.
   * @param force - probe even when the last probe is recent.
   * @returns the display state after the probe.
   */
  async probeDisplay(force = false) {
    const now = Date.now()
    if (!force && now - this.probedAt < 3000) return this.awake
    this.probedAt = now
    const panel = await this.panelState()
    if (panel !== null) {
      this.awake = panel
      return this.awake
    }
    // Fallback for ROMs whose SurfaceFlinger dump has no powerMode field.
    const display = await run('adb', ['shell', `cmd display get-displays | grep -o 'committedState [A-Z]*' | head -1`])
    if (display.ok && /committedState/.test(display.detail)) {
      this.awake = /committedState ON/.test(display.detail)
      return this.awake
    }
    const power = await run('adb', ['shell', 'dumpsys power | grep -m1 mWakefulness='])
    if (power.ok && /mWakefulness=/.test(power.detail)) {
      this.awake = /mWakefulness=Awake/.test(power.detail)
    }
    return this.awake
  }

  /**
   * Read the physical panel's power mode from SurfaceFlinger.
   *
   * `cmd display get-displays` reports DisplayManager's committed state, which
   * stays ON when scrcpy turns the panel off through SurfaceControl; the HWC
   * power mode is the state the user actually sees.
   * @returns true for on, false for off, null when the dump is unavailable.
   */
  async panelState() {
    const info = await run('adb', [
      'shell',
      "dumpsys SurfaceFlinger | grep -A80 '^Display ' | grep -m1 'powerMode='",
    ])
    if (!info.ok || !/powerMode=/.test(info.detail)) return null
    return /powerMode=On/.test(info.detail)
  }

  /** Wait until the panel reads off, or give up after `timeoutMs`. */
  async waitPanelOff(timeoutMs = 6000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if ((await this.panelState()) === false) return true
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
    return false
  }

  /**
   * Turn the panel off while keeping composition alive.
   *
   * `cmd display power-off` switches DisplayManager to OFF and stops the
   * compositor: screenrecord stops producing frames and screencap turns black.
   * scrcpy's SurfaceControl power-off only cuts the panel, so screenrecord keeps
   * streaming and the sidebar keeps its live picture. A scrcpy process is kept
   * alive as the holder; killing it restores the panel.
   * @returns the command outcome.
   */
  async startScreenOff() {
    if (this.holder !== null) {
      const off = (await this.panelState()) === false
      return { ok: off, detail: off ? '' : (this.holderDetail.trim() || 'scrcpy is still starting') }
    }
    const binary = findScrcpy()
    if (binary === null) {
      return { ok: false, detail: 'scrcpy not found: install it or set PHONE_MIRROR_SCRCPY' }
    }
    const args = ['--no-window', '--turn-screen-off', '--stay-awake', '--no-audio']
    if (process.env.ADB_SERIAL) args.unshift('-s', process.env.ADB_SERIAL)
    const child = spawn(binary, args, { stdio: ['ignore', 'ignore', 'pipe'] })
    this.holder = child
    this.holderDetail = ''
    child.stderr.on('data', (chunk) => {
      this.holderDetail = (this.holderDetail + chunk.toString()).slice(-600)
    })
    child.on('error', (error) => {
      this.holderDetail = error.message
      if (this.holder === child) this.holder = null
    })
    child.on('exit', () => {
      if (this.holder === child) this.holder = null
    })
    const off = await this.waitPanelOff()
    if (!off) {
      await this.stopScreenOff()
      return { ok: false, detail: this.holderDetail.trim() || 'scrcpy could not turn the panel off' }
    }
    return { ok: true, detail: '' }
  }

  /** Kill the scrcpy holder; scrcpy restores the panel power when it exits. */
  async stopScreenOff() {
    const child = this.holder
    this.holder = null
    if (child === null) return
    await new Promise((resolve) => {
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        resolve()
      }
      const timer = setTimeout(() => {
        try { child.kill('SIGKILL') } catch {}
        done()
      }, 2000)
      timer.unref?.()
      child.once('exit', () => {
        clearTimeout(timer)
        done()
      })
      try {
        child.kill('SIGTERM')
      } catch {
        clearTimeout(timer)
        done()
      }
    })
  }

  /**
   * Turn the device display off or on through adb.
   * @param state - `off` or `on`.
   * @returns the command outcome and the display state after it.
   */
  async setScreen(state) {
    const result = state === 'on' ? await this.wakeDisplay() : await this.sleepDisplay()
    if (state === 'on' && result.ok) this.resume()
    await new Promise((resolve) => setTimeout(resolve, 400))
    await this.probeDisplay(true)
    return { ok: result.ok, state, awake: this.awake, detail: result.detail }
  }

  /** @returns the command outcome. */
  async sleepDisplay() {
    return this.startScreenOff()
  }

  /**
   * Power the panel back on. Stopping the holder is enough because scrcpy
   * restores the display power on exit; fall back to display power-reset and
   * the wake key when it was not scrcpy that turned the panel off.
   * @returns the command outcome.
   */
  async wakeDisplay() {
    await this.stopScreenOff()
    await new Promise((resolve) => setTimeout(resolve, 300))
    let state = await this.panelState()
    if (state === false) {
      await run('adb', ['shell', `cmd display power-reset ${DISPLAY_ID}`])
      await run('adb', ['shell', `input keyevent ${KEY_WAKEUP}`])
      await new Promise((resolve) => setTimeout(resolve, 400))
      state = await this.panelState()
    }
    const ok = state !== false
    return { ok, detail: ok ? '' : 'could not turn the display back on' }
  }

  /** Start the pipeline again now, dropping any retry wait. */
  resume() {
    this.failures = 0
    if (this.restartTimer !== null) {
      clearTimeout(this.restartTimer)
      this.restartTimer = null
    }
    if (this.recorder === null && this.encoder === null && this.viewers.size > 0) this.start()
  }

  /** Schedule the pipeline stop that follows the last viewer. */
  scheduleIdleStop() {
    this.clearIdle()
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null
      this.killChildren()
      this.carry = Buffer.alloc(0)
    }, MIRROR.idleMs)
    this.idleTimer.unref?.()
  }

  clearIdle() {
    if (this.idleTimer === null) return
    clearTimeout(this.idleTimer)
    this.idleTimer = null
  }

  /** What the panel's status probe reports. */
  status() {
    return {
      running: this.recorder !== null || this.encoder !== null,
      viewers: this.viewers.size,
      fps: MIRROR.fps,
      awake: this.awake,
      screenOff: this.holder !== null,
      msSinceLastFrame: this.lastFrameAt === 0 ? null : Date.now() - this.lastFrameAt,
      error: null,
      detail: this.detail,
    }
  }

  /** Release every child, timer, and viewer this mirror owns. */
  dispose() {
    this.disposed = true
    this.clearIdle()
    if (this.restartTimer !== null) {
      clearTimeout(this.restartTimer)
      this.restartTimer = null
    }
    this.killChildren()
    void this.stopScreenOff()
    for (const viewer of [...this.viewers]) {
      try {
        viewer.res.end()
      } catch {}
    }
    this.viewers.clear()
  }
}

/**
 * Answer one JSON request.
 * @param res - the response to own.
 * @param status - the HTTP status.
 * @param value - the JSON body.
 */
function reply(res, status, value) {
  const body = Buffer.from(JSON.stringify(value))
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': String(body.length),
  })
  res.end(body)
}

/**
 * Answer one request under the bundle's route.
 * @param mirror - the mirror that owns the pipeline.
 * @param req - the incoming request.
 * @param res - the response to own.
 */
function serve(mirror, req, res) {
  const url = new URL(req.url ?? '/', 'http://localhost')
  if (url.pathname === SCREEN_PATH) {
    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'POST' })
      res.end()
      return
    }
    const state = url.searchParams.get('state')
    if (state !== 'on' && state !== 'off') {
      reply(res, 400, { ok: false, detail: 'state must be "on" or "off"' })
      return
    }
    mirror.setScreen(state).then(
      (value) => reply(res, value.ok ? 200 : 502, value),
      (error) => reply(res, 500, { ok: false, detail: String(error) }),
    )
    return
  }
  if (req.method !== 'GET') {
    res.writeHead(405, { allow: 'GET' })
    res.end()
    return
  }
  if (url.pathname === STREAM_PATH) {
    mirror.attach(res)
    return
  }
  if (url.pathname === STATUS_PATH) {
    mirror.probeDisplay().then(
      () => reply(res, 200, mirror.status()),
      () => reply(res, 200, mirror.status()),
    )
    return
  }
  if (url.pathname === SNAPSHOT_PATH) {
    const frame = mirror.lastFrame
    if (frame === null) {
      res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
      res.end('no frame yet\n')
      return
    }
    res.writeHead(200, {
      'content-type': 'image/jpeg',
      'cache-control': 'no-store',
      'content-length': String(frame.length),
    })
    res.end(frame)
    return
  }
  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
  res.end('not found\n')
}

/**
 * Mount the mirror's routes and own its pipeline.
 * @param ctx - host context carrying the browser HTTP carrier.
 */
export function mount(ctx) {
  const mirror = new PhoneMirror()
  for (const [path, label] of [
    [STREAM_PATH, 'stream'],
    [STATUS_PATH, 'status'],
    [SNAPSHOT_PATH, 'snapshot'],
    [SCREEN_PATH, 'screen'],
  ]) {
    ctx.effect(
      () => ctx.webServer.register({
        kind: 'exact',
        path,
        handler: (req, res) => serve(mirror, req, res),
      }),
      `phone-mirror: ${label} route`,
    )
  }
  ctx.effect(() => () => mirror.dispose(), 'phone-mirror: pipeline')
}

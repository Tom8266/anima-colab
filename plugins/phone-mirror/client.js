/**
 * Client half of the phone-mirror bundle: the right-sidebar tab that shows the
 * device mirror, plus the one control the panel owns — turning the phone screen
 * off and back on.
 *
 * The tab type registers through the sidebar's public path (the type in
 * `ctx.sidebarRightTabs`, the body in `sidebar.right.pane.tab`); the body is an
 * `<img>` over the host's multipart stream and a small toolbar button.
 */
window.__ModuleLoader__.load({
  id: '@local/phone-mirror',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    const NS = '@local/phone-mirror'
    const KIND = 'phone-screen'
    const STREAM_URL = '/phone-mirror/stream.mjpg'
    const STATUS_URL = '/phone-mirror/status.json'
    const SCREEN_URL = '/phone-mirror/screen'
    const AUTO_OPEN_KEY = 'dsh-phone-screen.auto-open.v3'
    const BUTTON_CLASS = 'phone-mirror-action'

    const DICTIONARIES = {
      en: {
        'tab.title': 'Phone screen',
        'guide.title': 'Phone screen',
        'guide.description': 'Mirror the attached Android device in this panel.',
        'alt': 'Live view of the phone screen',
        'connecting': 'Connecting to the device…',
        'noFrames': 'No picture yet. Is the phone screen awake?',
        'hostUnreachable': 'The mirror host is not reachable.',
        'screenOff': 'Turn screen off',
        'screenOn': 'Turn screen on',
        'screenFailed': 'Could not control the phone screen',
      },
      zh: {
        'tab.title': '手机屏幕',
        'guide.title': '手机屏幕',
        'guide.description': '在此面板中镜像已连接的安卓设备。',
        'alt': '手机屏幕实时画面',
        'connecting': '正在连接设备…',
        'noFrames': '还没有画面，请点亮手机屏幕。',
        'hostUnreachable': '无法连接镜像服务。',
        'screenOff': '熄灭屏幕',
        'screenOn': '点亮屏幕',
        'screenFailed': '无法控制手机屏幕',
      },
    }

    const styleSheet = `
      .${BUTTON_CLASS}:hover:not(:disabled) {
        color: var(--dsw-alias-label-primary);
        border-color: var(--dsw-alias-border-l2);
      }
      .${BUTTON_CLASS}:disabled { cursor: progress; }
    `

    const frameStyle = {
      position: 'relative',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: '100%',
      height: '100%',
      minWidth: 0,
      minHeight: 0,
      boxSizing: 'border-box',
      padding: '10px',
      overflow: 'hidden',
    }

    const imageStyle = {
      maxWidth: '100%',
      maxHeight: '100%',
      width: 'auto',
      height: 'auto',
      objectFit: 'contain',
      display: 'block',
      borderRadius: '12px',
      boxShadow: '0 0 0 1px var(--dsw-alias-border-l1)',
      background: 'var(--dsw-alias-bg-layer-1)',
    }

    const toolbarStyle = {
      position: 'absolute',
      top: '14px',
      right: '14px',
      zIndex: 2,
      display: 'flex',
      gap: '6px',
    }

    const buttonStyle = {
      display: 'inline-flex',
      alignItems: 'center',
      gap: '5px',
      height: '26px',
      padding: '0 10px',
      boxSizing: 'border-box',
      borderRadius: '999px',
      border: '1px solid var(--dsw-alias-border-l1)',
      background: 'var(--dsw-alias-bg-overlay)',
      color: 'var(--dsw-alias-label-secondary)',
      font: 'inherit',
      fontSize: '12px',
      lineHeight: 1,
      cursor: 'pointer',
      opacity: 0.94,
    }

    const overlayStyle = {
      position: 'absolute',
      inset: 0,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '28px',
      textAlign: 'center',
      font: 'inherit',
      fontSize: '13px',
      lineHeight: '1.5',
      pointerEvents: 'none',
      color: 'var(--dsw-alias-label-secondary)',
    }

    /** A crescent for "turn the screen off", a sun for "turn it back on". */
    function ScreenIcon(props) {
      const common = { width: 14, height: 14, viewBox: '0 0 16 16', 'aria-hidden': true, style: { flex: 'none' } }
      if (props.asleep) {
        const rays = []
        for (let index = 0; index < 8; index += 1) {
          const angle = (index * Math.PI) / 4
          rays.push(h('line', {
            key: index,
            x1: 8 + Math.cos(angle) * 4.6,
            y1: 8 + Math.sin(angle) * 4.6,
            x2: 8 + Math.cos(angle) * 6.3,
            y2: 8 + Math.sin(angle) * 6.3,
            stroke: 'currentColor',
            strokeWidth: 1.4,
            strokeLinecap: 'round',
          }))
        }
        return h('svg', common, h('circle', { cx: 8, cy: 8, r: 2.9, fill: 'currentColor' }), ...rays)
      }
      return h('svg', common, h('path', {
        d: 'M10.2 2.1a6.2 6.2 0 1 0 3.7 9.6A6.9 6.9 0 0 1 10.2 2.1Z',
        fill: 'currentColor',
      }))
    }

    /** The tab body: the live stream, the screen toggle, and the reason when no frame has arrived. */
    function PhoneBody(props) {
      const t = typeof props.t === 'function' ? props.t : (key) => key
      const [status, setStatus] = React.useState(null)
      const [loaded, setLoaded] = React.useState(false)
      const [slow, setSlow] = React.useState(false)
      const [failed, setFailed] = React.useState(false)
      const [nonce, setNonce] = React.useState(0)
      const [asleep, setAsleep] = React.useState(false)
      const [busy, setBusy] = React.useState(false)
      const [actionError, setActionError] = React.useState('')

      React.useEffect(() => {
        let current = true
        const probe = () => {
          fetch(STATUS_URL, { cache: 'no-store' })
            .then((response) => (response.ok ? response.json() : null))
            .then((value) => {
              if (!current) return
              if (value === null) {
                setStatus({ unreachable: true })
                return
              }
              setStatus(value)
              if (!busy && typeof value.awake === 'boolean') setAsleep(value.awake === false)
            })
            .catch(() => {
              if (current) setStatus({ unreachable: true })
            })
        }
        probe()
        const timer = window.setInterval(probe, 4000)
        return () => {
          current = false
          window.clearInterval(timer)
        }
      }, [busy])

      React.useEffect(() => {
        if (loaded) {
          setSlow(false)
          return undefined
        }
        const timer = window.setTimeout(() => setSlow(true), 8000)
        return () => window.clearTimeout(timer)
      }, [loaded, nonce])

      React.useEffect(() => {
        if (!failed) return undefined
        const timer = window.setTimeout(() => {
          setFailed(false)
          setNonce((value) => value + 1)
        }, 2000)
        return () => window.clearTimeout(timer)
      }, [failed])

      const toggle = () => {
        if (busy) return
        const next = asleep ? 'on' : 'off'
        setBusy(true)
        setActionError('')
        fetch(`${SCREEN_URL}?state=${next}`, { method: 'POST' })
          .then((response) => response.json().catch(() => null).then((value) => ({ ok: response.ok, value })))
          .then(({ ok, value }) => {
            if (ok && value && value.ok) {
              setAsleep(next === 'off')
              return
            }
            setActionError((value && value.detail) || t('screenFailed'))
          })
          .catch(() => setActionError(t('screenFailed')))
          .finally(() => setBusy(false))
      }

      const errorText = !status
        ? ''
        : status.unreachable
          ? t('hostUnreachable')
          : status.error
            ? String(status.error)
            : ''
      let overlay = ''
      if (actionError !== '') overlay = actionError
      else if (!loaded) overlay = errorText || (slow ? t('noFrames') : t('connecting'))
      const label = asleep ? t('screenOn') : t('screenOff')

      return h(
        'div',
        { style: frameStyle },
        h('style', null, styleSheet),
        h('img', {
          key: nonce,
          src: `${STREAM_URL}?v=${nonce}`,
          alt: t('alt'),
          draggable: false,
          onLoad: () => setLoaded(true),
          onError: () => {
            setLoaded(false)
            setFailed(true)
          },
          style: imageStyle,
        }),
        h(
          'div',
          { style: toolbarStyle },
          h(
            'button',
            {
              type: 'button',
              className: BUTTON_CLASS,
              onClick: toggle,
              disabled: busy,
              title: label,
              'aria-label': label,
              style: buttonStyle,
            },
            h(ScreenIcon, { asleep }),
            h('span', null, label),
          ),
        ),
        overlay === ''
          ? null
          : h(
              'div',
              {
                style: {
                  ...overlayStyle,
                  color: actionError !== '' || errorText !== ''
                    ? 'var(--dsw-alias-state-error-primary)'
                    : overlayStyle.color,
                },
              },
              h('span', null, overlay),
            ),
      )
    }

    /**
     * Open the panel once for a profile that has never seen it: a reload after
     * that restores the saved layout, and a tab the user closed stays closed.
     * @param ctx - the client context.
     */
    function autoOpen(ctx) {
      let timer = null
      let attempts = 0
      ctx.effect(() => () => {
        if (timer !== null) window.clearTimeout(timer)
      }, 'phone-mirror: auto-open timer')
      const attempt = () => {
        timer = null
        try {
          ctx.sidebarRight.openTab(KIND)
          try {
            window.localStorage.setItem(AUTO_OPEN_KEY, '1')
          } catch {}
          return
        } catch {}
        if (attempts++ < 40) timer = window.setTimeout(attempt, 500)
      }
      attempt()
    }

    return {
      inject: ['slots', 'locale', 'sidebarRightTabs', 'sidebarRight'],
      apply(ctx) {
        ctx.effect(() => ctx.locale.register(NS, DICTIONARIES), 'phone-mirror: dictionaries')
        const t = ctx.locale.bind(NS)
        ctx.effect(
          () => ctx.sidebarRightTabs.register({
            id: NS,
            kind: KIND,
            title: () => t('tab.title'),
            guide: [
              {
                id: 'phone-screen',
                order: 30,
                title: () => t('guide.title'),
                description: () => t('guide.description'),
              },
            ],
          }),
          'phone-mirror: tab type',
        )
        ctx.effect(
          () => ctx.slots.inject('sidebar.right.pane.tab', () => ctx.slots.register({
            name: 'sidebar.right.pane.tab',
            key: NS,
            inject: () => ({ t }),
          }, PhoneBody)),
          'phone-mirror: tab body',
        )
        let seen = false
        try {
          seen = window.localStorage.getItem(AUTO_OPEN_KEY) === '1'
        } catch {}
        if (!seen) autoOpen(ctx)
      },
    }
  },
})

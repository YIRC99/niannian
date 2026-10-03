/* Offline presentation controller. All slide visuals live in the stylesheet. */
(() => {
  'use strict';

  const instance = Symbol.for('niannian.presentation.player');
  if (window[instance]) return;
  window[instance] = true;

  const fit = () => document.documentElement.style.setProperty(
    '--fit', Math.min(window.innerWidth / 1440, window.innerHeight / 810)
  );
  fit();
  window.addEventListener('resize', fit);

  function initialize() {
    const slides = [...document.querySelectorAll('.deck .slide')];
    if (!slides.length) return;

    const query = new URLSearchParams(window.location.search);
    const exporting = query.get('export') === '1';
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const durations = slides.map(slide => {
      const duration = Number(slide.dataset.duration);
      return Number.isFinite(duration) && duration > 0 ? duration : 10000;
    });
    const steps = slides.map(slide => {
      const count = Number(slide.dataset.steps);
      return Number.isInteger(count) && count > 0 ? count : 1;
    });
    const controls = action => document.querySelectorAll('[data-action="' + action + '"]');
    const clamp = index => Math.max(0, Math.min(slides.length - 1, index));
    let current = Math.max(0, slides.findIndex(slide => slide.classList.contains('is-active')));
    let elapsed = 0;
    let playing = false;
    let frame = null;
    let startedAt = 0;
    let elapsedAtStart = 0;
    let status = document.querySelector('[data-player-status]');

    document.body.classList.toggle('export', exporting);
    slides.forEach(slide => { slide.dataset.phase = '0'; });

    function pageIndex(value) {
      if (!/^[1-9]\d*$/.test(value || '')) return null;
      const page = Number(value);
      return Number.isSafeInteger(page) ? clamp(page - 1) : null;
    }

    function hashIndex() {
      const match = /^#\/([1-9]\d*)$/.exec(window.location.hash);
      return match ? pageIndex(match[1]) : null;
    }

    function syncHash() {
      const hash = '#/' + (current + 1);
      if (window.location.hash === hash) return;
      try {
        window.history.replaceState(null, '', hash);
      } catch {
        window.location.hash = hash;
      }
    }

    function phase() {
      return Math.min(steps[current] - 1, Math.floor(elapsed / durations[current] * steps[current] + 1e-9));
    }

    function state() {
      return {
        current,
        phase: phase(),
        steps: steps[current],
        elapsed,
        duration: durations[current],
        playing,
        scene: slides[current].dataset.scene || '',
        title: slides[current].dataset.title || ''
      };
    }

    function updateControls() {
      document.body.classList.toggle('paused', !playing);
      controls('play').forEach(button => {
        button.textContent = playing ? '暂停 Ⅱ' : '播放 ▶';
        button.setAttribute('aria-label', playing ? '暂停播放' : '开始播放');
        button.setAttribute('aria-pressed', String(playing));
      });
      controls('prev').forEach(button => { button.disabled = current === 0; });
      controls('next').forEach(button => { button.disabled = current === slides.length - 1; });
      controls('step').forEach(button => {
        button.disabled = current === slides.length - 1 && phase() === steps[current] - 1;
      });
      document.querySelectorAll('[data-go]').forEach(button => {
        if (Number(button.dataset.go) === current) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
      });
    }

    function draw(force = false) {
      const slide = slides[current];
      const selected = phase();
      if (force || slide.dataset.phase !== String(selected)) {
        slide.dataset.phase = String(selected);
        slide.querySelectorAll('.process-step[data-phase]').forEach(button => {
          const active = Number(button.dataset.phase) === selected;
          button.classList.toggle('is-active', active);
          button.classList.toggle('active', active);
          button.setAttribute('aria-pressed', String(active));
        });
        document.querySelectorAll('[data-current-phase]').forEach(label => {
          if (!label.closest('.slide') || label.closest('.slide') === slide) {
            label.textContent = (selected + 1) + ' / ' + steps[current];
          }
        });
        updateControls();
      }
      const progress = (current + elapsed / durations[current]) / slides.length * 100;
      document.querySelectorAll('.progress-bar span').forEach(bar => {
        bar.style.width = progress + '%';
      });
    }

    function cancelTick() {
      if (frame !== null) window.cancelAnimationFrame(frame);
      frame = null;
    }

    function pause() {
      if (playing) {
        elapsed = Math.min(durations[current], elapsedAtStart + performance.now() - startedAt);
      }
      playing = false;
      cancelTick();
      draw();
      updateControls();
      return state();
    }

    function play() {
      if (document.hidden || playing) return state();
      if (elapsed >= durations[current]) go(current, false);
      playing = true;
      elapsedAtStart = elapsed;
      startedAt = performance.now();
      updateControls();
      cancelTick();
      frame = window.requestAnimationFrame(tick);
      return state();
    }

    function go(index, autoplay = playing) {
      const value = Number(index);
      if (!Number.isFinite(value)) return state();
      const previous = slides[current];
      const moveFocus = previous.contains(document.activeElement);
      cancelTick();
      playing = false;
      current = clamp(Math.trunc(value));
      elapsed = 0;
      slides.forEach(slide => {
        slide.classList.remove('is-active');
        slide.inert = true;
        slide.setAttribute('aria-hidden', 'true');
      });
      const active = slides[current];
      active.dataset.phase = '0';
      // Restart the CSS animation even when replaying the current slide.
      void active.offsetWidth;
      active.classList.add('is-active');
      active.inert = false;
      active.setAttribute('aria-hidden', 'false');
      if (moveFocus) {
        active.tabIndex = -1;
        active.focus({ preventScroll: true });
      }
      document.querySelectorAll('.slide-number').forEach(label => {
        label.textContent = String(current + 1).padStart(2, '0') + ' / ' + String(slides.length).padStart(2, '0');
      });
      syncHash();
      draw(true);
      if (autoplay) play();
      return state();
    }

    function tick(now) {
      frame = null;
      if (!playing) return;
      elapsed = Math.min(durations[current], elapsedAtStart + now - startedAt);
      draw();
      if (elapsed >= durations[current]) {
        if (current < slides.length - 1) go(current + 1, true);
        else pause();
        return;
      }
      frame = window.requestAnimationFrame(tick);
    }

    function selectPhase(value) {
      const selected = Number(value);
      if (!Number.isInteger(selected) || selected < 0 || selected >= steps[current]) return;
      pause();
      elapsed = selected * durations[current] / steps[current];
      draw(true);
    }

    function nextStep() {
      pause();
      if (phase() < steps[current] - 1) selectPhase(phase() + 1);
      else if (current < slides.length - 1) go(current + 1, false);
    }

    function announce(message) {
      if (!status) {
        status = document.createElement('p');
        status.dataset.playerStatus = '';
        document.body.append(status);
      }
      status.setAttribute('role', 'status');
      status.setAttribute('aria-live', 'polite');
      status.textContent = message;
    }

    async function fullscreen() {
      try {
        if (document.fullscreenElement) await document.exitFullscreen();
        else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
        else throw new Error('Fullscreen is unavailable');
        announce(document.fullscreenElement ? '已进入全屏，按 Esc 退出。' : '已退出全屏。');
      } catch {
        announce('当前环境无法切换全屏，请使用浏览器的全屏功能。');
      }
    }

    const actions = {
      prev: () => { if (current > 0) go(current - 1); },
      next: () => { if (current < slides.length - 1) go(current + 1); },
      play: () => playing ? pause() : play(),
      replay: () => go(current, true),
      start: () => go(0, true),
      step: nextStep,
      fullscreen
    };

    document.addEventListener('click', event => {
      const button = event.target.closest?.('.process-step[data-phase], [data-action], [data-go]');
      if (!button || button.disabled || button.closest('[inert]')) return;
      if (button.matches('.process-step[data-phase]')) {
        if (button.closest('.slide') === slides[current]) selectPhase(button.dataset.phase);
      } else if (button.hasAttribute('data-go')) {
        go(button.dataset.go);
      } else {
        actions[button.dataset.action]?.();
      }
    });

    document.addEventListener('keydown', event => {
      const target = event.target;
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
      if (target.isContentEditable || target.closest?.('input, textarea, select')) return;
      if (target.closest?.('button') && (event.key === ' ' || event.key === 'Enter')) return;
      const key = event.key.toLowerCase();
      const shortcuts = {
        arrowleft: actions.prev,
        arrowup: actions.prev,
        pageup: actions.prev,
        arrowright: actions.next,
        arrowdown: actions.next,
        pagedown: actions.next,
        home: () => go(0),
        end: () => go(slides.length - 1),
        ' ': actions.play,
        r: actions.replay,
        f: fullscreen
      };
      if (!shortcuts[key]) return;
      event.preventDefault();
      if (!event.repeat) shortcuts[key]();
    });

    window.addEventListener('hashchange', () => {
      const index = hashIndex();
      if (index !== null && index !== current) go(index);
      else syncHash();
    });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) pause();
    });
    reducedMotion.addEventListener?.('change', event => {
      if (event.matches) pause();
    });

    window.preview = {
      go: index => go(index),
      pause,
      play,
      state,
      capture(index) {
        pause();
        go(index, false);
        return new Promise(resolve => {
          if (document.hidden) resolve(state());
          else window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve(state())));
        });
      }
    };

    const selected = exporting ? pageIndex(query.get('slide')) ?? hashIndex() : hashIndex();
    go(selected ?? current, !exporting && !reducedMotion.matches && !document.hidden);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();
})();

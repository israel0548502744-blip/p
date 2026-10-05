import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  Columns2,
  Maximize,
  Pause,
  Play,
  ShieldCheck,
  SplitSquareHorizontal,
  Volume2,
  VolumeX,
} from "lucide-react";
import { cx, formatDuration } from "../format";

export type CompareMode = "split" | "wipe" | "censored";

interface Props {
  originalUrl: string;
  censoredUrl?: string | null;
  width: number;
  height: number;
  fps: number;
  mode: CompareMode;
  onModeChange?: (m: CompareMode) => void;
  timeline?: number[];
}

const GAP = 12;

/** Before/after player: both videos are driven by one transport and kept frame-synchronized. */
export function Player({ originalUrl, censoredUrl, width, height, fps, mode, onModeChange, timeline }: Props) {
  const compare = !!censoredUrl;
  const effectiveMode: CompareMode | "single" = compare ? mode : "single";
  const stageRef = useRef<HTMLDivElement>(null);
  const origRef = useRef<HTMLVideoElement>(null);
  const censRef = useRef<HTMLVideoElement>(null);
  const [box, setBox] = useState({ w: 0, h: 0 });
  const [playing, setPlaying] = useState(false);
  const [current, setCurrent] = useState(0);
  const [duration, setDuration] = useState(0);
  const [muted, setMuted] = useState(false);
  const [volume, setVolume] = useState(0.9);
  const [wipe, setWipe] = useState(50);
  const [dragging, setDragging] = useState(false);
  const aspect = width / Math.max(1, height);

  const master = useCallback(() => (compare ? censRef.current : origRef.current), [compare]);
  const slave = useCallback(() => (compare ? origRef.current : null), [compare]);

  // Fit the video box(es) into the available stage area.
  useLayoutEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const fit = () => {
      const W = el.clientWidth;
      const H = el.clientHeight;
      if (effectiveMode === "split") {
        const w = Math.min((W - GAP) / 2, H * aspect);
        setBox({ w, h: w / aspect });
      } else {
        const w = Math.min(W, H * aspect);
        setBox({ w, h: w / aspect });
      }
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [aspect, effectiveMode]);

  // Sync loop: keeps the follower locked to the master's clock.
  useEffect(() => {
    let raf = 0;
    const tick = () => {
      const m = master();
      const s = slave();
      if (m) {
        setCurrent(m.currentTime);
        if (s && s.readyState >= 2) {
          const drift = s.currentTime - m.currentTime;
          if (Math.abs(drift) > 0.25 || (m.paused && Math.abs(drift) > 0.01)) {
            s.currentTime = m.currentTime;
            s.playbackRate = m.playbackRate;
          } else if (!m.paused && Math.abs(drift) > 0.03) {
            s.playbackRate = m.playbackRate * (drift > 0 ? 0.94 : 1.06); // gentle catch-up
          } else {
            s.playbackRate = m.playbackRate;
          }
          if (!m.paused && s.paused && !m.ended) s.play().catch(() => {});
          if (m.paused && !s.paused) s.pause();
        }
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [master, slave]);

  // Audio: only the master is audible.
  useEffect(() => {
    const m = master();
    const s = slave();
    if (m) {
      m.muted = muted;
      m.volume = volume;
    }
    if (s) s.muted = true;
  }, [master, slave, muted, volume, compare]);

  // Reset when sources change.
  useEffect(() => {
    setPlaying(false);
    setCurrent(0);
  }, [originalUrl, censoredUrl]);

  const toggle = useCallback(() => {
    const m = master();
    if (!m) return;
    if (m.paused || m.ended) {
      const s = slave();
      if (m.ended) m.currentTime = 0;
      if (s) s.currentTime = m.currentTime;
      m.play().catch(() => {});
      s?.play().catch(() => {});
    } else {
      m.pause();
      slave()?.pause();
    }
  }, [master, slave]);

  const seek = useCallback(
    (t: number) => {
      const m = master();
      if (!m) return;
      const d = m.duration || duration;
      const v = Math.max(0, Math.min(d || 0, t));
      m.currentTime = v;
      const s = slave();
      if (s) s.currentTime = v;
      setCurrent(v);
    },
    [master, slave, duration],
  );

  const step = useCallback(
    (dir: number) => {
      const m = master();
      if (!m) return;
      m.pause();
      slave()?.pause();
      seek(m.currentTime + dir / Math.max(1, fps));
    },
    [master, slave, seek, fps],
  );

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "BUTTON") return;
      if (e.code === "Space") {
        e.preventDefault();
        toggle();
      } else if (e.code === "ArrowLeft") step(e.shiftKey ? -10 : -1);
      else if (e.code === "ArrowRight") step(e.shiftKey ? 10 : 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggle, step]);

  // Wipe-slider dragging
  const wipeBox = useRef<HTMLDivElement>(null);
  const onWipeMove = useCallback((clientX: number) => {
    const el = wipeBox.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    setWipe(Math.max(0, Math.min(100, ((clientX - r.left) / r.width) * 100)));
  }, []);
  useEffect(() => {
    if (!dragging) return;
    const move = (e: PointerEvent) => onWipeMove(e.clientX);
    const up = () => setDragging(false);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [dragging, onWipeMove]);

  const fullscreen = () => {
    const el = stageRef.current?.parentElement;
    if (!el) return;
    if (document.fullscreenElement) document.exitFullscreen();
    else el.requestFullscreen?.();
  };

  const isWipe = effectiveMode === "wipe";
  const isSplit = effectiveMode === "split";
  const showOriginal = effectiveMode !== "censored";

  const wrapperBase = "overflow-hidden rounded-xl bg-black shadow-[0_30px_60px_-30px_rgba(0,0,0,0.9)] ring-1 ring-white/5";
  const label = (text: string, accent: boolean, side: "left" | "right") => (
    <div
      className={cx(
        "pointer-events-none absolute top-3 z-10 flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wider backdrop-blur-md",
        side === "left" ? "left-3" : "right-3",
        accent ? "bg-shield-600/80 text-white" : "bg-black/55 text-ink-100",
      )}
    >
      {accent && <ShieldCheck className="h-3 w-3" />}
      {text}
    </div>
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div ref={stageRef} className="relative min-h-0 flex-1">
        <div className="absolute inset-0 flex items-center justify-center">
          <div
            ref={wipeBox}
            className={cx("relative", isSplit && "flex")}
            style={{
              width: isSplit ? box.w * 2 + GAP : box.w,
              height: box.h,
              gap: isSplit ? GAP : undefined,
            }}
            onClick={(e) => {
              if (!isWipe && (e.target as HTMLElement).tagName === "VIDEO") toggle();
            }}
          >
            {/* Censored (master when available) */}
            {compare && (
              <div
                className={cx(wrapperBase, "relative", isSplit ? "order-2" : "absolute inset-0")}
                style={isSplit ? { width: box.w, height: box.h } : undefined}
              >
                <video
                  ref={censRef}
                  src={censoredUrl ?? undefined}
                  className="h-full w-full object-contain"
                  playsInline
                  preload="auto"
                  onPlay={() => setPlaying(true)}
                  onPause={() => setPlaying(false)}
                  onEnded={() => setPlaying(false)}
                  onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
                />
                {label("Censored", true, "right")}
              </div>
            )}
            {/* Original */}
            <div
              className={cx(
                wrapperBase,
                compare && !isSplit ? "absolute inset-0" : "relative",
                isSplit && "order-1",
                !showOriginal && "invisible",
                isWipe && "rounded-r-none ring-0 shadow-none",
              )}
              style={{
                ...(isSplit || !compare ? { width: box.w, height: box.h } : {}),
                ...(isWipe ? { clipPath: `inset(0 ${100 - wipe}% 0 0)` } : {}),
              }}
            >
              <video
                ref={origRef}
                src={originalUrl}
                className="h-full w-full object-contain"
                playsInline
                preload="auto"
                onPlay={() => !compare && setPlaying(true)}
                onPause={() => !compare && setPlaying(false)}
                onEnded={() => !compare && setPlaying(false)}
                onLoadedMetadata={(e) => !compare && setDuration(e.currentTarget.duration)}
              />
              {compare && label("Original", false, "left")}
            </div>

            {isWipe && (
              <div
                className="absolute inset-y-0 z-20 -ml-5 w-10 cursor-ew-resize touch-none"
                style={{ left: `${wipe}%` }}
                onPointerDown={(e) => {
                  e.preventDefault();
                  setDragging(true);
                  onWipeMove(e.clientX);
                }}
              >
                <div className="absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 bg-white shadow-[0_0_12px_rgba(0,0,0,0.6)]" />
                <div
                  className={cx(
                    "absolute top-1/2 left-1/2 grid h-10 w-10 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full bg-white text-ink-950 shadow-xl transition-transform",
                    dragging && "scale-110",
                  )}
                >
                  <SplitSquareHorizontal className="h-4.5 w-4.5" />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Transport */}
      <div className="mt-3 shrink-0 rounded-2xl border border-white/5 bg-ink-900/80 px-3 py-2.5 backdrop-blur">
        <SeekBar current={current} duration={duration} onSeek={seek} timeline={timeline} />
        <div className="mt-2 flex items-center gap-1">
          <button
            onClick={toggle}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-white text-ink-950 transition hover:scale-105 active:scale-95"
            title="Play / pause (Space)"
          >
            {playing ? <Pause className="h-4 w-4 fill-current" /> : <Play className="ml-0.5 h-4 w-4 fill-current" />}
          </button>
          <button onClick={() => step(-1)} className="ctrl-btn" title="Previous frame (←)">
            <ChevronLeft className="h-4 w-4" />
          </button>
          <button onClick={() => step(1)} className="ctrl-btn" title="Next frame (→)">
            <ChevronRight className="h-4 w-4" />
          </button>
          <div className="ml-2 whitespace-nowrap font-mono text-[12.5px] tabular-nums text-ink-200">
            {formatDuration(current, true)} <span className="text-ink-500">/ {formatDuration(duration)}</span>
          </div>
          <div className="flex-1" />
          {compare && onModeChange && (
            <div className="mr-1 flex shrink-0 rounded-lg bg-white/[0.04] p-0.5">
              {(
                [
                  ["split", "Side by side", <Columns2 key="a" className="h-3.5 w-3.5" />],
                  ["wipe", "Slider", <SplitSquareHorizontal key="b" className="h-3.5 w-3.5" />],
                  ["censored", "Censored", <ShieldCheck key="c" className="h-3.5 w-3.5" />],
                ] as const
              ).map(([m, text, icon]) => (
                <button
                  key={m}
                  onClick={() => onModeChange(m)}
                  className={cx(
                    "flex items-center gap-1.5 whitespace-nowrap rounded-md px-2.5 py-1.5 text-[12px] font-medium transition",
                    mode === m ? "bg-ink-600 text-white" : "text-ink-300 hover:text-white",
                  )}
                >
                  {icon}
                  <span className="hidden lg:inline">{text}</span>
                </button>
              ))}
            </div>
          )}
          <button onClick={() => setMuted((v) => !v)} className="ctrl-btn" title="Mute">
            {muted || volume === 0 ? <VolumeX className="h-4 w-4" /> : <Volume2 className="h-4 w-4" />}
          </button>
          <input
            type="range"
            className="slider hidden !w-20 shrink-0 sm:block"
            min={0}
            max={100}
            value={muted ? 0 : Math.round(volume * 100)}
            style={{ ["--fill" as string]: `${muted ? 0 : volume * 100}%` }}
            onChange={(e) => {
              setVolume(Number(e.target.value) / 100);
              setMuted(false);
            }}
          />
          <button onClick={fullscreen} className="ctrl-btn" title="Fullscreen">
            <Maximize className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

function SeekBar({
  current,
  duration,
  onSeek,
  timeline,
}: {
  current: number;
  duration: number;
  onSeek: (t: number) => void;
  timeline?: number[];
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const pct = duration > 0 ? (current / duration) * 100 : 0;

  const at = useCallback(
    (clientX: number) => {
      const r = ref.current!.getBoundingClientRect();
      return Math.max(0, Math.min(1, (clientX - r.left) / r.width)) * duration;
    },
    [duration],
  );

  useEffect(() => {
    if (!drag) return;
    const move = (e: PointerEvent) => onSeek(at(e.clientX));
    const up = () => setDrag(false);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [drag, at, onSeek]);

  const maxT = timeline && timeline.length ? Math.max(...timeline, 0.0001) : 1;

  return (
    <div
      ref={ref}
      className="group relative h-7 cursor-pointer touch-none select-none"
      onPointerDown={(e) => {
        setDrag(true);
        onSeek(at(e.clientX));
      }}
      onPointerMove={(e) => duration > 0 && setHover(at(e.clientX))}
      onPointerLeave={() => setHover(null)}
    >
      {timeline && timeline.length > 0 && (
        <div className="absolute inset-x-0 top-0 flex h-3.5 items-end opacity-80" title="Censored area over time">
          {timeline.map((v, i) => (
            <div
              key={i}
              className="flex-1 bg-shield-500"
              style={{ height: v > 0 ? `${Math.max(12, (v / maxT) * 100)}%` : 0, opacity: v > 0 ? 0.35 + 0.65 * (v / maxT) : 0 }}
            />
          ))}
        </div>
      )}
      <div className="absolute inset-x-0 bottom-1.5 h-1.5 overflow-hidden rounded-full bg-ink-600 transition-all group-hover:h-2">
        <div className="h-full rounded-full bg-gradient-to-r from-shield-500 to-shield-300" style={{ width: `${pct}%` }} />
      </div>
      <div
        className="absolute bottom-[3px] h-3.5 w-3.5 -translate-x-1/2 rounded-full bg-white shadow opacity-0 transition group-hover:opacity-100"
        style={{ left: `${pct}%` }}
      />
      {hover != null && (
        <div
          className="pointer-events-none absolute -top-6 -translate-x-1/2 rounded bg-black/80 px-1.5 py-0.5 font-mono text-[10.5px] text-white"
          style={{ left: `${(hover / Math.max(duration, 0.001)) * 100}%` }}
        >
          {formatDuration(hover)}
        </div>
      )}
    </div>
  );
}

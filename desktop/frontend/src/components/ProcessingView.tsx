import { useEffect, useState } from "react";
import { Check, Clock, Film, Gauge, Loader2, Pause, Play, Square } from "lucide-react";
import type { Job } from "../types";
import { cx, formatDuration, formatEta } from "../format";

const STEPS = [
  { key: "analyzing", label: "Analyzing video…", sub: "Reading frames, loading models" },
  { key: "detecting", label: "Detecting sensitive regions…", sub: "People · gender · skin · tracking" },
  { key: "applying", label: "Applying censorship…", sub: "Feathered blue masks" },
  { key: "encoding", label: "Encoding final video…", sub: "H.264 · original FPS · audio" },
] as const;

function stepIndex(stage: Job["stage"]): number {
  switch (stage) {
    case "queued":
    case "analyzing":
      return 0;
    case "detecting":
      return 1;
    case "applying":
      return 2;
    case "encoding":
      return 3;
    case "complete":
      return 4;
    default:
      return -1;
  }
}

function Ring({ value }: { value: number }) {
  const r = 52;
  const c = 2 * Math.PI * r;
  return (
    <div className="relative h-32 w-32 shrink-0">
      <svg viewBox="0 0 120 120" className="h-full w-full -rotate-90">
        <defs>
          <linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#9db6ff" />
            <stop offset="100%" stopColor="#1e4dff" />
          </linearGradient>
        </defs>
        <circle cx="60" cy="60" r={r} stroke="rgba(255,255,255,0.07)" strokeWidth="9" fill="none" />
        <circle
          cx="60"
          cy="60"
          r={r}
          stroke="url(#ringGrad)"
          strokeWidth="9"
          strokeLinecap="round"
          fill="none"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - value / 100)}
          style={{ transition: "stroke-dashoffset 0.5s ease" }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <div className="text-[28px] font-semibold tabular-nums tracking-tight text-white">
          {Math.floor(value)}
          <span className="text-base text-ink-300">%</span>
        </div>
      </div>
    </div>
  );
}

export function ProcessingView({
  job,
  onPause,
  onResume,
  onCancel,
}: {
  job: Job;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
}) {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setTick((x) => x + 1), 700);
    return () => clearInterval(t);
  }, []);
  const active = stepIndex(job.stage);
  const previewSrc = job.has_preview ? `/api/jobs/${job.id}/preview.jpg?t=${tick}` : null;
  const current = STEPS[Math.max(0, Math.min(3, active))];

  return (
    <div className="relative flex h-full min-h-0 flex-col gap-4 animate-fade-in">
      {/* Live preview */}
      <div className="relative min-h-0 flex-1 overflow-hidden rounded-2xl ring-1 ring-white/5">
        <div className="absolute inset-0 bg-black/40" />
        {previewSrc ? (
          <LivePreview src={previewSrc} />
        ) : (
          <div className="absolute inset-0 grid place-items-center">
            <Loader2 className="h-8 w-8 animate-spin text-shield-300" />
          </div>
        )}
        {!job.paused && (
          <div className="pointer-events-none absolute inset-x-0 top-0 h-[10%] animate-scan bg-gradient-to-b from-transparent via-shield-400/25 to-transparent" />
        )}
        <div className="absolute top-3 left-3 flex items-center gap-2 rounded-full bg-black/60 px-3 py-1.5 text-[11.5px] font-medium text-ink-100 backdrop-blur-md">
          <span className={cx("h-2 w-2 rounded-full", job.paused ? "bg-amber-300" : "animate-pulse bg-red-500")} />
          {job.paused ? "Paused" : job.pass_index === 1 ? "Live detection preview" : "Live output preview"}
        </div>
      </div>

      {/* Progress card */}
      <div className="panel shrink-0 p-5">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-center">
          <Ring value={job.percent} />
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline gap-3">
              <h2 className="text-xl font-semibold tracking-tight text-white">
                {job.paused ? "Paused" : job.stage === "queued" ? "Waiting in queue…" : current.label}
              </h2>
            </div>
            <div className="relative mt-3 h-2 overflow-hidden rounded-full bg-ink-700">
              <div
                className="h-full rounded-full bg-gradient-to-r from-shield-600 via-shield-500 to-shield-300 transition-[width] duration-500"
                style={{ width: `${job.percent}%` }}
              />
              {!job.paused && <div className="shimmer-bar absolute inset-0 animate-shimmer opacity-40" />}
            </div>
            <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Metric
                icon={<Film className="h-3.5 w-3.5" />}
                label={job.pass_index === 2 ? "Rendering frame" : "Analyzing frame"}
                value={`${job.frame.toLocaleString()} / ${job.total_frames.toLocaleString()}`}
              />
              <Metric icon={<Clock className="h-3.5 w-3.5" />} label="Remaining" value={job.paused ? "Paused" : formatEta(job.eta_seconds)} />
              <Metric icon={<Gauge className="h-3.5 w-3.5" />} label="Speed" value={job.fps ? `${job.fps} fps` : "—"} />
              <Metric icon={<Clock className="h-3.5 w-3.5" />} label="Elapsed" value={formatDuration(job.elapsed)} />
            </div>
          </div>
          <div className="flex shrink-0 gap-2 lg:flex-col">
            {job.paused ? (
              <button onClick={onResume} className="btn-secondary">
                <Play className="h-4 w-4 fill-current" /> Resume
              </button>
            ) : (
              <button onClick={onPause} className="btn-secondary" disabled={job.stage === "encoding" || job.stage === "queued"}>
                <Pause className="h-4 w-4 fill-current" /> Pause
              </button>
            )}
            <button onClick={onCancel} className="btn-danger">
              <Square className="h-3.5 w-3.5 fill-current" /> Cancel
            </button>
          </div>
        </div>

        <ol className="mt-5 grid grid-cols-1 gap-2 border-t border-white/5 pt-4 sm:grid-cols-2 xl:grid-cols-4">
          {STEPS.map((s, i) => {
            const done = i < active;
            const now = i === active;
            return (
              <li
                key={s.key}
                className={cx(
                  "flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors duration-500",
                  now ? "bg-shield-500/10 ring-1 ring-shield-400/25" : "bg-white/[0.02]",
                )}
              >
                <div
                  className={cx(
                    "grid h-7 w-7 shrink-0 place-items-center rounded-full transition-all duration-500",
                    done ? "bg-emerald-500 text-white" : now ? "bg-shield-500 text-white" : "bg-ink-700 text-ink-400",
                  )}
                >
                  {done ? (
                    <Check className="h-4 w-4" strokeWidth={3} />
                  ) : now && !job.paused ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <span className="text-[11px] font-semibold">{i + 1}</span>
                  )}
                </div>
                <div className="min-w-0">
                  <div className={cx("truncate text-[13px] font-medium", done || now ? "text-white" : "text-ink-400")}>{s.label}</div>
                  <div className="truncate text-[11px] text-ink-400">{s.sub}</div>
                </div>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}

/** Double-buffered image so the live preview never flashes while the next frame loads. */
function LivePreview({ src }: { src: string }) {
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    const img = new Image();
    img.onload = () => setShown(src);
    img.src = src;
  }, [src]);
  return shown ? <img src={shown} alt="" className="absolute inset-0 h-full w-full object-contain" /> : null;
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-xl bg-white/[0.03] px-3 py-2">
      <div className="flex items-center gap-1.5 text-[10.5px] font-medium uppercase tracking-wider text-ink-400">
        {icon}
        {label}
      </div>
      <div className="mt-0.5 truncate font-mono text-[13.5px] tabular-nums text-ink-100">{value}</div>
    </div>
  );
}

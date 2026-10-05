import { CheckCircle2, Download, FileVideo, RefreshCcw, ShieldCheck, Upload } from "lucide-react";
import type { Job, VideoMeta } from "../types";
import { formatDuration } from "../format";

export function ResultPanel({
  job,
  video,
  onAdjust,
  onNew,
}: {
  job: Job;
  video: VideoMeta;
  onAdjust: () => void;
  onNew: () => void;
}) {
  const r = job.result;
  const pct = r ? Math.round((r.censored_frames / Math.max(1, r.frames)) * 100) : 0;
  const filename = `${video.name.replace(/\.[^.]+$/, "")}_blueshield.mp4`;
  return (
    <aside className="panel flex min-h-0 animate-rise flex-col overflow-hidden">
      <div className="relative overflow-hidden border-b border-white/5 px-5 py-5">
        <div className="pointer-events-none absolute -top-16 -right-10 h-40 w-40 rounded-full bg-emerald-400/20 blur-3xl" />
        <div className="flex items-center gap-3">
          <div className="grid h-11 w-11 place-items-center rounded-2xl bg-emerald-500/15 text-emerald-300">
            <CheckCircle2 className="h-6 w-6" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-white">Censorship complete.</h2>
            <p className="text-[12.5px] text-ink-300">Review the result, then export.</p>
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
        <a
          href={job.download_url ?? "#"}
          download={filename}
          className="group relative flex w-full items-center justify-center gap-2.5 overflow-hidden rounded-xl bg-gradient-to-b from-shield-500 to-shield-600 px-4 py-4 text-[16px] font-semibold text-white shadow-[0_16px_40px_-12px_rgba(30,77,255,0.9)] transition hover:brightness-110 active:scale-[0.99]"
        >
          <span className="shimmer-bar pointer-events-none absolute inset-0 animate-shimmer opacity-30" />
          <Download className="h-5 w-5" />
          Export MP4
        </a>

        <div className="rounded-xl border border-white/5 bg-white/[0.02] p-3.5">
          <div className="mb-2.5 flex items-center gap-2 text-[13px] font-medium text-white">
            <FileVideo className="h-4 w-4 text-shield-300" />
            <span className="truncate">{filename}</span>
          </div>
          <dl className="grid grid-cols-2 gap-y-1.5 text-[12px]">
            <dt className="text-ink-400">Container</dt>
            <dd className="text-right text-ink-100">MP4</dd>
            <dt className="text-ink-400">Video codec</dt>
            <dd className="text-right text-ink-100">H.264 {r?.encoder && r.encoder !== "libx264" ? `(${r.encoder})` : ""}</dd>
            <dt className="text-ink-400">Resolution</dt>
            <dd className="text-right text-ink-100">
              {video.width}×{video.height}
            </dd>
            <dt className="text-ink-400">Frame rate</dt>
            <dd className="text-right text-ink-100">{+video.fps.toFixed(3)} fps</dd>
            <dt className="text-ink-400">Audio</dt>
            <dd className="text-right text-ink-100">
              {video.has_audio ? (job.settings.keep_audio ? "Preserved (AAC)" : "Removed") : "None in source"}
            </dd>
          </dl>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <Stat label="Frames censored" value={r ? `${r.censored_frames.toLocaleString()}` : "—"} sub={`${pct}% of video`} />
          <Stat label="Processing time" value={r ? formatDuration(r.elapsed) : "—"} sub={r ? `${(r.frames / Math.max(r.elapsed, 0.01)).toFixed(1)} fps` : ""} />
        </div>

        <div className="flex items-start gap-2.5 rounded-xl bg-shield-500/[0.06] p-3 text-[12px] leading-snug text-ink-200">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-shield-300" />
          The blue censorship is burned into every frame of the exported file. It was produced entirely on this machine.
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 border-t border-white/5 p-4">
        <button onClick={onAdjust} className="btn-secondary justify-center whitespace-nowrap">
          <RefreshCcw className="h-4 w-4" /> Re-run
        </button>
        <button onClick={onNew} className="btn-secondary justify-center whitespace-nowrap">
          <Upload className="h-4 w-4" /> New video
        </button>
      </div>
    </aside>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="rounded-xl bg-white/[0.03] px-3 py-2.5">
      <div className="text-[10.5px] font-medium uppercase tracking-wider text-ink-400">{label}</div>
      <div className="mt-0.5 text-[17px] font-semibold tabular-nums text-white">{value}</div>
      <div className="text-[11px] text-ink-400">{sub}</div>
    </div>
  );
}

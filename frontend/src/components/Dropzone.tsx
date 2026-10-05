import { useCallback, useRef, useState } from "react";
import { Film, Lock, UploadCloud } from "lucide-react";
import { cx } from "../format";

const ACCEPT = ".mp4,.m4v,.mov,.webm,.mkv,.avi,.wmv,.flv,.mpg,.mpeg,.3gp,.ts,.mts,.m2ts,.ogv,video/*";

export function Dropzone({
  onFile,
  importing,
  importProgress,
  error,
}: {
  onFile: (f: File) => void;
  importing: boolean;
  importProgress: number;
  error: string | null;
}) {
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const pick = useCallback(
    (files: FileList | null) => {
      const f = files?.[0];
      if (f) onFile(f);
    },
    [onFile],
  );

  return (
    <div
      className={cx(
        "group relative flex h-full w-full flex-col items-center justify-center overflow-hidden rounded-[18px] border-2 border-dashed transition-all duration-300",
        over ? "scale-[0.995] border-shield-400 bg-shield-500/[0.08]" : "border-white/10 hover:border-white/20",
      )}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        if (!importing) pick(e.dataTransfer.files);
      }}
    >
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(600px_300px_at_50%_40%,rgba(30,77,255,0.12),transparent_70%)]" />
      <div className="relative flex max-w-md animate-rise flex-col items-center px-6 text-center">
        <div className="relative mb-6">
          <div className="absolute inset-0 animate-pulse-ring rounded-3xl bg-shield-500/30" />
          <div className="relative grid h-20 w-20 place-items-center rounded-3xl bg-gradient-to-br from-shield-400 to-shield-700 shadow-[0_20px_50px_-12px_rgba(30,77,255,0.7)]">
            {importing ? <Film className="h-9 w-9 text-white" /> : <UploadCloud className="h-9 w-9 text-white" />}
          </div>
        </div>
        {importing ? (
          <>
            <h2 className="text-xl font-semibold text-white">Importing video…</h2>
            <p className="mt-2 text-sm text-ink-300">Copying into the local workspace on this machine.</p>
            <div className="mt-6 h-1.5 w-72 overflow-hidden rounded-full bg-ink-700">
              <div
                className="h-full rounded-full bg-gradient-to-r from-shield-500 to-shield-300 transition-[width] duration-200"
                style={{ width: `${Math.round(importProgress * 100)}%` }}
              />
            </div>
            <div className="mt-2 font-mono text-xs text-ink-300">{Math.round(importProgress * 100)}%</div>
          </>
        ) : (
          <>
            <h2 className="text-2xl font-semibold tracking-tight text-white">Drop a video to begin</h2>
            <p className="mt-2 text-[15px] leading-relaxed text-ink-300">
              BlueShield finds exposed skin and sensitive regions frame by frame and covers them with a clean blue mask
              that follows every movement.
            </p>
            <button
              onClick={() => input.current?.click()}
              className="mt-7 inline-flex items-center gap-2 rounded-xl bg-white px-5 py-3 text-sm font-semibold text-ink-950 shadow-lg transition hover:-translate-y-0.5 hover:shadow-xl active:translate-y-0"
            >
              <UploadCloud className="h-4 w-4" />
              Upload Video
            </button>
            <div className="mt-4 text-xs text-ink-400">MP4 · MOV · WebM · MKV · AVI and more</div>
            <div className="mt-6 inline-flex items-center gap-1.5 text-xs text-emerald-300/90">
              <Lock className="h-3.5 w-3.5" /> Your videos are processed locally and are not uploaded.
            </div>
          </>
        )}
        {error && (
          <div className="mt-6 rounded-xl border border-red-400/30 bg-red-500/10 px-4 py-2.5 text-sm text-red-200">{error}</div>
        )}
      </div>
      <input ref={input} type="file" accept={ACCEPT} className="hidden" onChange={(e) => pick(e.target.files)} />
    </div>
  );
}

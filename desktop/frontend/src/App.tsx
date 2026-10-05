import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, ImageIcon, Loader2, Lock, X } from "lucide-react";
import { api } from "./api";
import type { CensorSettings, Health, Job, Override, VideoMeta } from "./types";
import { DEFAULT_SETTINGS } from "./types";
import { Header } from "./components/Header";
import { Dropzone } from "./components/Dropzone";
import { VideoInfoBar } from "./components/VideoInfoBar";
import { SettingsPanel } from "./components/SettingsPanel";
import { Player, type CompareMode } from "./components/Player";
import { ProcessingView } from "./components/ProcessingView";
import { ResultPanel } from "./components/ResultPanel";

type View = "edit" | "processing" | "result";
const TERMINAL = new Set(["complete", "error", "cancelled"]);
const SETTINGS_KEY = "blueshield.settings.v1";

function loadSettings(): CensorSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
  } catch {
    /* ignore */
  }
  return DEFAULT_SETTINGS;
}

export default function App() {
  const [health, setHealth] = useState<Health | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [video, setVideo] = useState<VideoMeta | null>(null);
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState(0);
  const [importError, setImportError] = useState<string | null>(null);
  const [settings, setSettings] = useState<CensorSettings>(loadSettings);
  const [job, setJob] = useState<Job | null>(null);
  const [view, setView] = useState<View>("edit");
  const [mode, setMode] = useState<CompareMode>("split");
  const [notice, setNotice] = useState<{ kind: "error" | "info"; text: string } | null>(null);
  const abortImport = useRef<(() => void) | null>(null);

  // Engine health
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const h = await api.health();
        if (!alive) return;
        setHealth(h);
        setHealthError(null);
        timer = setTimeout(poll, 20000);
      } catch (e) {
        if (!alive) return;
        setHealthError((e as Error).message);
        timer = setTimeout(poll, 3000);
      }
    };
    poll();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    } catch {
      /* ignore */
    }
  }, [settings]);

  // Wait for a browser-playable preview proxy when the source format needs one.
  useEffect(() => {
    if (!video || video.preview_ready || video.proxy_state === "failed") return;
    const t = setInterval(async () => {
      try {
        const v = await api.video(video.id);
        if (v.preview_ready || v.proxy_state === "failed") setVideo(v);
      } catch {
        /* ignore */
      }
    }, 1000);
    return () => clearInterval(t);
  }, [video]);

  // Job polling
  useEffect(() => {
    if (!job || TERMINAL.has(job.stage)) return;
    let alive = true;
    const t = setInterval(async () => {
      try {
        const j = await api.job(job.id);
        if (!alive) return;
        setJob(j);
        if (j.stage === "complete") {
          if (j.kind === "render" && j.message.startsWith("Re-render cancelled"))
            setNotice({ kind: "info", text: j.message });
          setView("result");
          setMode("split");
        } else if (j.stage === "error") {
          setView("edit");
          setNotice({ kind: "error", text: `Processing failed: ${j.error ?? "unknown error"}` });
        } else if (j.stage === "cancelled") {
          setView("edit");
          setNotice({ kind: "info", text: "Processing cancelled." });
        }
      } catch {
        /* transient */
      }
    }, 500);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [job?.id, job?.stage]); // eslint-disable-line react-hooks/exhaustive-deps

  const onFile = useCallback((file: File) => {
    setImportError(null);
    setNotice(null);
    setImporting(true);
    setImportProgress(0);
    const { promise, abort } = api.upload(file, setImportProgress);
    abortImport.current = abort;
    promise
      .then((v) => {
        setVideo(v);
        setJob(null);
        setView("edit");
      })
      .catch((e: Error) => setImportError(e.message))
      .finally(() => {
        setImporting(false);
        abortImport.current = null;
      });
  }, []);

  const start = useCallback(async () => {
    if (!video) return;
    setNotice(null);
    try {
      const j = await api.startJob(video.id, settings);
      setJob(j);
      setView("processing");
    } catch (e) {
      setNotice({ kind: "error", text: (e as Error).message });
    }
  }, [video, settings]);

  const control = useCallback(
    async (action: "pause" | "resume" | "cancel") => {
      if (!job) return;
      try {
        setJob(await api.control(job.id, action));
      } catch (e) {
        setNotice({ kind: "error", text: (e as Error).message });
      }
    },
    [job],
  );

  const rerender = useCallback(
    async (overrides: Record<string, Override>) => {
      if (!job) return;
      setNotice(null);
      try {
        const j = await api.rerender(job.id, overrides);
        setJob(j);
        setView("processing");
      } catch (e) {
        setNotice({ kind: "error", text: (e as Error).message });
      }
    },
    [job],
  );

  const clear = useCallback(() => {
    setVideo(null);
    setJob(null);
    setView("edit");
    setNotice(null);
  }, []);

  const processing = view === "processing";
  const engineReady = !!health && !healthError;

  return (
    <div className="app-bg flex h-full flex-col">
      <Header health={health} healthError={healthError} />

      <main className="flex min-h-0 flex-1 flex-col gap-4 p-4 lg:flex-row lg:p-5">
        {/* Stage column */}
        <div className="flex min-h-[60vh] min-w-0 flex-1 flex-col gap-3 lg:min-h-0">
          {notice && (
            <div
              className={
                "flex animate-rise items-center gap-3 rounded-xl border px-4 py-2.5 text-sm " +
                (notice.kind === "error"
                  ? "border-red-400/30 bg-red-500/10 text-red-100"
                  : "border-white/10 bg-white/[0.04] text-ink-100")
              }
            >
              <AlertTriangle className="h-4 w-4 shrink-0" />
              <span className="flex-1">{notice.text}</span>
              <button onClick={() => setNotice(null)} className="text-ink-300 hover:text-white">
                <X className="h-4 w-4" />
              </button>
            </div>
          )}

          <div className="panel stage relative min-h-0 flex-1 p-3 sm:p-4">
            {!video ? (
              <Dropzone onFile={onFile} importing={importing} importProgress={importProgress} error={importError} />
            ) : processing && job ? (
              <ProcessingView
                job={job}
                onPause={() => control("pause")}
                onResume={() => control("resume")}
                onCancel={() => control("cancel")}
              />
            ) : view === "result" && job?.output_url ? (
              <Player
                key={job.id}
                originalUrl={video.preview_url}
                censoredUrl={job.output_url}
                width={video.width}
                height={video.height}
                fps={video.fps}
                mode={mode}
                onModeChange={setMode}
                timeline={job.timeline}
              />
            ) : video.preview_ready ? (
              <Player
                key={video.id}
                originalUrl={video.preview_url}
                width={video.width}
                height={video.height}
                fps={video.fps}
                mode="split"
              />
            ) : (
              <PreviewPending video={video} />
            )}
          </div>

          {video && <VideoInfoBar video={video} onClear={clear} locked={processing} />}
        </div>

        {/* Sidebar */}
        <div className="flex min-h-0 w-full shrink-0 flex-col lg:w-[360px]">
          {view === "result" && job && video ? (
            <ResultPanel job={job} video={video} onAdjust={() => setView("edit")} onNew={clear} onRerender={rerender} />
          ) : (
            <SettingsPanel
              settings={settings}
              onChange={setSettings}
              disabled={processing}
              canStart={!!video && !processing && engineReady && !importing}
              onStart={start}
              hasAudio={!!video?.has_audio}
            />
          )}
          <div className="mt-3 flex items-center justify-center gap-1.5 text-[11.5px] text-ink-400 md:hidden">
            <Lock className="h-3 w-3" /> Your videos are processed locally and are not uploaded.
          </div>
        </div>
      </main>
    </div>
  );
}

function PreviewPending({ video }: { video: VideoMeta }) {
  return (
    <div className="relative grid h-full place-items-center overflow-hidden rounded-xl">
      <img src={video.thumbnail_url} alt="" className="absolute inset-0 h-full w-full object-contain opacity-40 blur-sm" />
      <div className="relative flex flex-col items-center gap-3 rounded-2xl bg-black/60 px-6 py-5 text-center backdrop-blur-md">
        {video.proxy_state === "failed" ? (
          <>
            <ImageIcon className="h-6 w-6 text-ink-300" />
            <div className="text-sm text-ink-100">Preview unavailable for this format — processing still works.</div>
          </>
        ) : (
          <>
            <Loader2 className="h-6 w-6 animate-spin text-shield-300" />
            <div className="text-sm text-ink-100">Preparing a playable preview…</div>
            <div className="text-xs text-ink-400">{video.codec.toUpperCase()} isn't natively playable in browsers</div>
          </>
        )}
      </div>
    </div>
  );
}

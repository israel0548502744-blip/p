import { Clock, FileVideo, Gauge, HardDrive, Maximize2, Music2, VolumeX, X } from "lucide-react";
import type { ReactNode } from "react";
import type { VideoMeta } from "../types";
import { formatBytes, formatDuration, resolutionLabel } from "../format";

function Chip({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="flex min-w-0 items-center gap-2.5 rounded-xl bg-white/[0.03] px-3 py-2">
      <div className="text-ink-400">{icon}</div>
      <div className="min-w-0 leading-tight">
        <div className="text-[10.5px] font-medium uppercase tracking-wider text-ink-400">{label}</div>
        <div className="truncate text-[13px] font-medium text-ink-100">{value}</div>
      </div>
    </div>
  );
}

export function VideoInfoBar({ video, onClear, locked }: { video: VideoMeta; onClear: () => void; locked: boolean }) {
  return (
    <div className="panel flex animate-fade-in flex-wrap items-center gap-2 p-2">
      <div className="flex min-w-[180px] flex-1 items-center gap-3 px-2">
        <div className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-shield-500/15 text-shield-300">
          <FileVideo className="h-4.5 w-4.5" />
        </div>
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-white" title={video.name}>
            {video.name}
          </div>
          <div className="text-[11.5px] uppercase tracking-wide text-ink-400">
            {video.codec} · {video.container.split(",")[0]}
          </div>
        </div>
      </div>
      <Chip icon={<Clock className="h-4 w-4" />} label="Duration" value={formatDuration(video.duration)} />
      <Chip
        icon={<Maximize2 className="h-4 w-4" />}
        label="Resolution"
        value={`${video.width}×${video.height} · ${resolutionLabel(video.width, video.height)}`}
      />
      <Chip icon={<Gauge className="h-4 w-4" />} label="Frame rate" value={`${+video.fps.toFixed(2)} fps`} />
      <Chip icon={<HardDrive className="h-4 w-4" />} label="Size" value={formatBytes(video.size)} />
      <Chip
        icon={video.has_audio ? <Music2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
        label="Audio"
        value={video.has_audio ? (video.audio_codec ?? "yes").toUpperCase() : "None"}
      />
      <button
        onClick={onClear}
        disabled={locked}
        title="Remove video"
        className="ml-auto grid h-9 w-9 place-items-center rounded-lg text-ink-300 transition hover:bg-white/5 hover:text-white disabled:pointer-events-none disabled:opacity-30"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}

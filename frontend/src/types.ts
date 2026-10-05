export interface VideoMeta {
  id: string;
  name: string;
  duration: number;
  width: number;
  height: number;
  fps: number;
  fps_str: string;
  frames: number;
  codec: string;
  container: string;
  has_audio: boolean;
  audio_codec: string | null;
  size: number;
  rotation: number;
  bitrate: number | null;
  browser_playable: boolean;
  preview_ready: boolean;
  proxy_state: "none" | "building" | "ready" | "failed";
  source_url: string;
  preview_url: string;
  thumbnail_url: string;
}

export type Speed = "quality" | "balanced" | "fast";
export type Quality = "high" | "balanced" | "small";

export interface CensorSettings {
  color: string;
  sensitivity: number;
  softness: number;
  aggressive: boolean;
  animated: boolean;
  include_face: boolean;
  speed: Speed;
  quality: Quality;
  keep_audio: boolean;
}

export type Stage =
  | "queued"
  | "analyzing"
  | "detecting"
  | "applying"
  | "encoding"
  | "complete"
  | "error"
  | "cancelled";

export interface JobResult {
  frames: number;
  censored_frames: number;
  encoder: string;
  elapsed: number;
}

export interface Job {
  id: string;
  video_id: string;
  stage: Stage;
  paused: boolean;
  percent: number;
  frame: number;
  total_frames: number;
  pass_index: number;
  eta_seconds: number | null;
  fps: number;
  elapsed: number;
  message: string;
  timeline: number[];
  settings: CensorSettings;
  error: string | null;
  result: JobResult | null;
  output_url: string | null;
  download_url: string | null;
  has_preview: boolean;
}

export interface Health {
  ok: boolean;
  version: string;
  ffmpeg: boolean;
  models: Record<string, { name: string; ready: boolean; source: string; license: string }>;
  acceleration: { onnx_providers: string[]; gpu: boolean; gpu_name: string | null };
  encoders: string[];
  local_only: boolean;
}

export const DEFAULT_SETTINGS: CensorSettings = {
  color: "#1E4DFF",
  sensitivity: 60,
  softness: 35,
  aggressive: false,
  animated: false,
  include_face: false,
  speed: "balanced",
  quality: "balanced",
  keep_audio: true,
};

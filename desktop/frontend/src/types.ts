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

export type Target = "female" | "everyone";
export type UncertainPolicy = "censor" | "keep";
export type Override = "auto" | "censor" | "keep";

export interface PersonInfo {
  id: number;
  gender: "female" | "male" | "uncertain";
  p_female: number;
  confidence: number;
  votes: number;
  censored: boolean;
  override: Override;
  frames: number;
  start: number;
  end: number;
  has_thumbnail: boolean;
  /** Median estimated age (null when no face was seen). */
  age?: number | null;
  /** Classified as a child: not censored when only women are. */
  child?: boolean;
  thumbnail_url: string | null;
}

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
  target: Target;
  gender_threshold: number;
  uncertain_policy: UncertainPolicy;
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
  people: PersonInfo[];
  overrides: Record<string, Override>;
  can_rerender: boolean;
  kind: "full" | "render";
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
  softness: 20,
  aggressive: false,
  animated: false,
  include_face: false,
  speed: "balanced",
  quality: "balanced",
  keep_audio: true,
  target: "female",
  gender_threshold: 70,
  uncertain_policy: "censor",
};

import type { CensorSettings, Health, Job, VideoMeta } from "./types";

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.detail) detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
    } catch {
      /* ignore */
    }
    throw new Error(detail);
  }
  return res.json() as Promise<T>;
}

export const api = {
  health: () => fetch("/api/health").then((r) => json<Health>(r)),
  video: (id: string) => fetch(`/api/videos/${id}`).then((r) => json<VideoMeta>(r)),
  job: (id: string) => fetch(`/api/jobs/${id}`).then((r) => json<Job>(r)),
  startJob: (video_id: string, settings: CensorSettings) =>
    fetch("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ video_id, settings }),
    }).then((r) => json<Job>(r)),
  control: (id: string, action: "pause" | "resume" | "cancel") =>
    fetch(`/api/jobs/${id}/${action}`, { method: "POST" }).then((r) => json<Job>(r)),

  /** Hands the file to the local engine (localhost only) with progress reporting. */
  upload(file: File, onProgress: (fraction: number) => void): { promise: Promise<VideoMeta>; abort: () => void } {
    const xhr = new XMLHttpRequest();
    const promise = new Promise<VideoMeta>((resolve, reject) => {
      const form = new FormData();
      form.append("file", file, file.name);
      xhr.open("POST", "/api/videos");
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
      xhr.onload = () => {
        let body: unknown = null;
        try {
          body = JSON.parse(xhr.responseText);
        } catch {
          /* ignore */
        }
        if (xhr.status >= 200 && xhr.status < 300) resolve(body as VideoMeta);
        else reject(new Error((body as { detail?: string })?.detail ?? `Import failed (${xhr.status})`));
      };
      xhr.onerror = () => reject(new Error("Could not reach the local BlueShield engine. Is it running?"));
      xhr.onabort = () => reject(new Error("Import cancelled"));
      xhr.send(form);
    });
    return { promise, abort: () => xhr.abort() };
  },
};

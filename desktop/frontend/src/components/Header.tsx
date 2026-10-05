import { Cpu, Lock, ShieldCheck, Zap } from "lucide-react";
import type { Health } from "../types";
import { cx } from "../format";

export function Logo() {
  return (
    <div className="flex items-center gap-2.5">
      <div className="relative grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-shield-400 to-shield-700 shadow-[0_8px_24px_-8px_rgba(30,77,255,0.8)]">
        <ShieldCheck className="h-5 w-5 text-white" strokeWidth={2.2} />
      </div>
      <div className="leading-tight">
        <div className="text-[15px] font-semibold tracking-tight text-white">BlueShield</div>
        <div className="text-[11px] text-ink-300">Automatic video censorship</div>
      </div>
    </div>
  );
}

export function Header({ health, healthError }: { health: Health | null; healthError: string | null }) {
  const ready = health && health.ffmpeg && Object.values(health.models).every((m) => m.ready);
  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-white/5 px-5">
      <Logo />
      <div className="hidden items-center gap-2 rounded-full border border-emerald-400/20 bg-emerald-400/[0.07] px-3.5 py-1.5 text-[12.5px] text-emerald-200 md:flex">
        <Lock className="h-3.5 w-3.5" />
        Your videos are processed locally and are not uploaded.
      </div>
      <div className="flex items-center gap-2">
        {health && (
          <span
            className="hidden items-center gap-1.5 rounded-full border border-white/8 bg-white/[0.03] px-3 py-1.5 text-[12px] text-ink-200 sm:flex"
            title={`Inference: ${health.acceleration.onnx_providers.join(", ")}\nEncoders: ${health.encoders.join(", ")}`}
          >
            {health.acceleration.gpu ? <Zap className="h-3.5 w-3.5 text-amber-300" /> : <Cpu className="h-3.5 w-3.5" />}
            {health.acceleration.gpu ? "GPU accelerated" : "CPU mode"}
          </span>
        )}
        <span
          className={cx(
            "flex items-center gap-2 rounded-full border px-3 py-1.5 text-[12px]",
            healthError
              ? "border-red-400/30 bg-red-400/10 text-red-200"
              : ready
                ? "border-white/8 bg-white/[0.03] text-ink-200"
                : "border-amber-400/30 bg-amber-400/10 text-amber-100",
          )}
        >
          <span className="relative flex h-2 w-2">
            {ready && !healthError && (
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
            )}
            <span
              className={cx(
                "relative inline-flex h-2 w-2 rounded-full",
                healthError ? "bg-red-400" : ready ? "bg-emerald-400" : "bg-amber-300",
              )}
            />
          </span>
          {healthError ? "Engine offline" : ready ? "Engine ready" : "Starting engine…"}
        </span>
      </div>
    </header>
  );
}

import { Flame, Play, RotateCcw, ScanFace, ShieldQuestion, Sparkles, Users, Volume2 } from "lucide-react";
import type { ReactNode } from "react";
import type { CensorSettings, Quality, Speed, Target, UncertainPolicy } from "../types";
import { DEFAULT_SETTINGS } from "../types";
import { cx } from "../format";

const SWATCHES = ["#FFFFFF", "#000000", "#6B7280", "#E8D9C5", "#1E4DFF", "#0B1F66", "#10B981", "#F472B6"];

function Section({ title, children, right }: { title: string; children: ReactNode; right?: ReactNode }) {
  return (
    <section className="border-b border-white/5 px-5 py-4 last:border-b-0">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-400">{title}</h3>
        {right}
      </div>
      {children}
    </section>
  );
}

function Slider({
  label,
  value,
  onChange,
  disabled,
  hint,
  left,
  rightLabel,
  min = 0,
  max = 100,
  suffix = "",
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  disabled?: boolean;
  hint?: string;
  left: string;
  rightLabel: string;
  min?: number;
  max?: number;
  suffix?: string;
}) {
  const fill = ((value - min) / (max - min)) * 100;
  return (
    <div className="mb-4 last:mb-0">
      <div className="mb-1 flex items-baseline justify-between">
        <label className="text-[13.5px] font-medium text-ink-100">{label}</label>
        <span className="rounded-md bg-white/5 px-1.5 py-0.5 font-mono text-[11.5px] text-ink-200">
          {value}
          {suffix}
        </span>
      </div>
      <input
        type="range"
        className="slider"
        min={min}
        max={max}
        value={value}
        disabled={disabled}
        style={{ ["--fill" as string]: `${fill}%` }}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <div className="flex justify-between text-[11px] text-ink-400">
        <span>{left}</span>
        <span>{rightLabel}</span>
      </div>
      {hint && <p className="mt-1 text-[11.5px] leading-snug text-ink-400">{hint}</p>}
    </div>
  );
}

function Toggle({
  icon,
  label,
  description,
  checked,
  onChange,
  disabled,
  accent = "shield",
}: {
  icon: ReactNode;
  label: string;
  description: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
  accent?: "shield" | "orange";
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cx(
        "mb-2 flex w-full items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition last:mb-0 disabled:opacity-50",
        checked
          ? accent === "orange"
            ? "border-orange-400/30 bg-orange-400/[0.07]"
            : "border-shield-400/30 bg-shield-500/[0.08]"
          : "border-white/5 bg-white/[0.02] hover:border-white/10",
      )}
    >
      <div
        className={cx(
          "grid h-8 w-8 shrink-0 place-items-center rounded-lg",
          checked ? (accent === "orange" ? "bg-orange-400/15 text-orange-300" : "bg-shield-500/20 text-shield-300") : "bg-white/5 text-ink-300",
        )}
      >
        {icon}
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-[13.5px] font-medium text-ink-100">{label}</div>
        <div className="text-[11.5px] leading-snug text-ink-400">{description}</div>
      </div>
      <div
        className={cx(
          "relative h-5 w-9 shrink-0 rounded-full transition-colors",
          checked ? (accent === "orange" ? "bg-orange-500" : "bg-shield-500") : "bg-ink-600",
        )}
      >
        <div
          className={cx(
            "absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform",
            checked ? "translate-x-[18px]" : "translate-x-0.5",
          )}
        />
      </div>
    </button>
  );
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
  disabled,
}: {
  value: T;
  options: { value: T; label: string; title?: string }[];
  onChange: (v: T) => void;
  disabled?: boolean;
}) {
  return (
    <div className="grid rounded-xl bg-white/[0.04] p-1" style={{ gridTemplateColumns: `repeat(${options.length}, 1fr)` }}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          title={o.title}
          disabled={disabled}
          onClick={() => onChange(o.value)}
          className={cx(
            "rounded-lg px-2 py-1.5 text-[12.5px] font-medium transition disabled:opacity-50",
            value === o.value ? "bg-ink-600 text-white shadow" : "text-ink-300 hover:text-ink-100",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function SettingsPanel({
  settings,
  onChange,
  disabled,
  canStart,
  onStart,
  hasAudio,
}: {
  settings: CensorSettings;
  onChange: (s: CensorSettings) => void;
  disabled: boolean;
  canStart: boolean;
  onStart: () => void;
  hasAudio: boolean;
}) {
  const set = <K extends keyof CensorSettings>(k: K, v: CensorSettings[K]) => onChange({ ...settings, [k]: v });
  const isDefault = JSON.stringify(settings) === JSON.stringify(DEFAULT_SETTINGS);

  return (
    <aside className="panel flex min-h-0 flex-col overflow-hidden">
      <div className="flex items-center justify-between border-b border-white/5 px-5 py-4">
        <div>
          <h2 className="text-[15px] font-semibold text-white">Censor settings</h2>
          <p className="text-[12px] text-ink-400">Tune detection and the blue mask</p>
        </div>
        <button
          onClick={() => onChange(DEFAULT_SETTINGS)}
          disabled={disabled || isDefault}
          title="Reset to defaults"
          className="grid h-8 w-8 place-items-center rounded-lg text-ink-300 transition hover:bg-white/5 hover:text-white disabled:opacity-30"
        >
          <RotateCcw className="h-4 w-4" />
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <Section
          title="Censor color"
          right={<span className="font-mono text-[11.5px] uppercase text-ink-300">{settings.color}</span>}
        >
          <div className="flex items-center gap-2">
            {SWATCHES.map((c) => (
              <button
                key={c}
                disabled={disabled}
                onClick={() => set("color", c)}
                title={c}
                className={cx(
                  "h-8 w-8 rounded-[10px] transition hover:scale-110 disabled:opacity-50",
                  settings.color.toLowerCase() === c.toLowerCase()
                    ? "ring-2 ring-white ring-offset-2 ring-offset-ink-900"
                    : "ring-1 ring-white/10",
                )}
                style={{ background: c }}
              />
            ))}
            <label
              className="relative h-8 w-8 overflow-hidden rounded-[10px] ring-1 ring-white/15"
              title="Custom color"
              style={{ background: "conic-gradient(from 90deg, #1E4DFF, #00B3FF, #7c3aed, #1E4DFF)" }}
            >
              <input
                type="color"
                className="swatch-input absolute inset-0 h-full w-full opacity-0"
                value={settings.color}
                disabled={disabled}
                onChange={(e) => set("color", e.target.value.toUpperCase())}
              />
            </label>
          </div>
          <div className="mt-3">
            <Toggle
              icon={<Sparkles className="h-4 w-4" />}
              label="Animated shimmer"
              description="Gentle moving sheen instead of a flat fill"
              checked={settings.animated}
              onChange={(v) => set("animated", v)}
              disabled={disabled}
            />
          </div>
        </Section>

        <Section title="Who to censor" right={<Users className="h-3.5 w-3.5 text-ink-400" />}>
          <Segmented<Target>
            value={settings.target}
            disabled={disabled}
            onChange={(v) => set("target", v)}
            options={[
              { value: "female", label: "Women only", title: "Censor only people classified as female" },
              { value: "everyone", label: "Everyone", title: "Censor every detected person" },
            ]}
          />
          {settings.target === "female" ? (
            <div className="mt-4">
              <Slider
                label="Gender confidence"
                value={settings.gender_threshold}
                min={51}
                max={99}
                suffix="%"
                onChange={(v) => set("gender_threshold", v)}
                disabled={disabled}
                left="Decide quickly"
                rightLabel="Must be very sure"
                hint="A person counts as female or male only above this confidence; anyone below it is “unsure”."
              />
              <div className="mb-1.5 flex items-center gap-1.5 text-[13.5px] font-medium text-ink-100">
                <ShieldQuestion className="h-4 w-4 text-ink-300" /> When unsure
              </div>
              <Segmented<UncertainPolicy>
                value={settings.uncertain_policy}
                disabled={disabled}
                onChange={(v) => set("uncertain_policy", v)}
                options={[
                  { value: "censor", label: "Censor (safe)", title: "Censor people the classifier is unsure about" },
                  { value: "keep", label: "Don't censor", title: "Leave uncertain people uncensored" },
                ]}
              />
              <p className="mt-2 text-[11.5px] leading-snug text-ink-400">
                Gender is estimated from faces and is not always right. Review the people list after processing — you can
                override anyone and re-render instantly.
              </p>
            </div>
          ) : (
            <p className="mt-2 text-[11.5px] leading-snug text-ink-400">Every detected person is censored.</p>
          )}
        </Section>

        <Section title="Detection">
          <Slider
            label="Detection sensitivity"
            value={settings.sensitivity}
            onChange={(v) => set("sensitivity", v)}
            disabled={disabled}
            left="Precise"
            rightLabel="Catch everything"
            hint="Higher values censor more borderline skin and lower-confidence regions."
          />
          <Slider
            label="Mask softness"
            value={settings.softness}
            onChange={(v) => set("softness", v)}
            disabled={disabled}
            left="Hard edge"
            rightLabel="Feathered"
          />
          <div className="mt-4">
            <Toggle
              icon={<Flame className="h-4 w-4" />}
              label="Aggressive censorship"
              description="Wider masks, every-frame detection, tiled scan for small people, color-based skin backup"
              checked={settings.aggressive}
              onChange={(v) => set("aggressive", v)}
              disabled={disabled}
              accent="orange"
            />
            <Toggle
              icon={<ScanFace className="h-4 w-4" />}
              label="Also cover faces"
              description="Include facial skin in the mask"
              checked={settings.include_face}
              onChange={(v) => set("include_face", v)}
              disabled={disabled}
            />
          </div>
        </Section>

        <Section title="Performance">
          <Segmented<Speed>
            value={settings.speed}
            disabled={disabled || settings.aggressive}
            onChange={(v) => set("speed", v)}
            options={[
              { value: "quality", label: "Max quality", title: "Segment every frame" },
              { value: "balanced", label: "Balanced", title: "Segment every 2nd frame, track in between" },
              { value: "fast", label: "Fast", title: "Segment every 4th frame, track in between" },
            ]}
          />
          <p className="mt-2 text-[11.5px] leading-snug text-ink-400">
            {settings.aggressive
              ? "Aggressive mode always analyzes every frame."
              : "Optical-flow tracking carries masks between analyzed frames."}
          </p>
        </Section>

        <Section title="Export">
          <Segmented<Quality>
            value={settings.quality}
            disabled={disabled}
            onChange={(v) => set("quality", v)}
            options={[
              { value: "high", label: "High" },
              { value: "balanced", label: "Standard" },
              { value: "small", label: "Small file" },
            ]}
          />
          <div className="mt-3 space-y-1 text-[12px] text-ink-300">
            <div className="flex justify-between">
              <span>Format</span>
              <span className="text-ink-100">MP4 · H.264</span>
            </div>
            <div className="flex justify-between">
              <span>Resolution & FPS</span>
              <span className="text-ink-100">Original</span>
            </div>
          </div>
          {hasAudio && (
            <div className="mt-3">
              <Toggle
                icon={<Volume2 className="h-4 w-4" />}
                label="Preserve audio"
                description="Keep the original soundtrack"
                checked={settings.keep_audio}
                onChange={(v) => set("keep_audio", v)}
                disabled={disabled}
              />
            </div>
          )}
        </Section>
      </div>

      <div className="border-t border-white/5 p-4">
        <button
          onClick={onStart}
          disabled={!canStart}
          className="group relative flex w-full items-center justify-center gap-2 overflow-hidden rounded-xl bg-gradient-to-b from-shield-500 to-shield-600 px-4 py-3.5 text-[15px] font-semibold text-white shadow-[0_12px_30px_-10px_rgba(30,77,255,0.8)] transition hover:brightness-110 active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none"
        >
          <span className="shimmer-bar pointer-events-none absolute inset-0 opacity-0 transition group-hover:animate-shimmer group-hover:opacity-100" />
          <Play className="h-4 w-4 fill-current" />
          Start Censoring
        </button>
      </div>
    </aside>
  );
}

import { useEffect, useMemo, useState } from "react";
import { RefreshCcw, ShieldCheck, ShieldOff, User } from "lucide-react";
import type { Job, Override, PersonInfo } from "../types";
import { cx, formatDuration } from "../format";

const GENDER_TEXT: Record<PersonInfo["gender"], string> = {
  female: "Female",
  male: "Male",
  uncertain: "Unsure",
};

/** Detected people, their estimated gender + confidence, and manual censor overrides. */
export function PeoplePanel({ job, onApply }: { job: Job; onApply: (o: Record<string, Override>) => void }) {
  const [draft, setDraft] = useState<Record<string, Override>>({});
  useEffect(() => {
    const init: Record<string, Override> = {};
    for (const p of job.people) init[String(p.id)] = p.override;
    setDraft(init);
  }, [job.people, job.output_url]);

  const dirty = useMemo(
    () => job.people.some((p) => (draft[String(p.id)] ?? "auto") !== p.override),
    [draft, job.people],
  );
  const target = job.settings.target;

  if (job.people.length === 0) {
    return (
      <div className="rounded-xl border border-white/5 bg-white/[0.02] p-3.5 text-[12.5px] text-ink-300">
        No people were detected.{" "}
        {target === "female" &&
          (job.settings.uncertain_policy === "censor"
            ? "Skin that couldn't be linked to a person was censored (safe fallback)."
            : "Unlinked skin was left uncensored.")}
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-white/5 bg-white/[0.02]">
      <div className="flex items-center justify-between px-3.5 pt-3 pb-2">
        <div className="text-[13px] font-semibold text-white">People ({job.people.length})</div>
        <div className="text-[11px] text-ink-400">
          {target === "female" ? `Women only · ≥${job.settings.gender_threshold}%` : "Everyone"}
        </div>
      </div>
      <ul className="max-h-[320px] space-y-1.5 overflow-y-auto px-2 pb-2">
        {job.people.map((p) => {
          const ov = draft[String(p.id)] ?? "auto";
          const willCensor = ov === "censor" ? true : ov === "keep" ? false : autoDecision(p, job);
          return (
            <li key={p.id} className="flex items-center gap-2.5 rounded-lg bg-white/[0.03] p-2">
              <div className="relative h-12 w-10 shrink-0 overflow-hidden rounded-md bg-ink-700">
                {p.thumbnail_url ? (
                  <img src={p.thumbnail_url} alt="" className="h-full w-full object-cover" />
                ) : (
                  <User className="m-auto mt-3 h-5 w-5 text-ink-400" />
                )}
                <div
                  className={cx(
                    "absolute right-0.5 bottom-0.5 grid h-4 w-4 place-items-center rounded-full",
                    willCensor ? "bg-shield-500 text-white" : "bg-ink-500 text-ink-100",
                  )}
                >
                  {willCensor ? <ShieldCheck className="h-2.5 w-2.5" /> : <ShieldOff className="h-2.5 w-2.5" />}
                </div>
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline gap-1.5">
                  <span className="text-[12.5px] font-semibold text-white">#{p.id}</span>
                  <span
                    className={cx(
                      "text-[12px] font-medium",
                      p.gender === "uncertain" ? "text-amber-200" : "text-ink-100",
                    )}
                    title={`${p.votes} face observations · P(female) = ${(p.p_female * 100).toFixed(1)}%`}
                  >
                    {p.child ? (p.gender === "female" ? "Girl" : "Child") : GENDER_TEXT[p.gender]}
                    {p.gender !== "uncertain" && ` ${Math.round(p.confidence * 100)}%`}
                    {p.age != null && ` · ~${Math.round(p.age)} y`}
                  </span>
                </div>
                <div className="text-[10.5px] text-ink-400">
                  {formatDuration(p.start)}–{formatDuration(p.end)} · {p.votes} face looks
                </div>
                <div className="mt-1 grid grid-cols-3 rounded-md bg-black/20 p-0.5">
                  {(["auto", "censor", "keep"] as const).map((o) => (
                    <button
                      key={o}
                      onClick={() => setDraft((d) => ({ ...d, [String(p.id)]: o }))}
                      className={cx(
                        "rounded px-1 py-0.5 text-[10.5px] font-medium transition",
                        ov === o ? "bg-ink-600 text-white" : "text-ink-400 hover:text-ink-100",
                      )}
                    >
                      {o === "auto" ? "Auto" : o === "censor" ? "Censor" : "Don't"}
                    </button>
                  ))}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
      {dirty && (
        <div className="border-t border-white/5 p-2">
          <button
            onClick={() => onApply(draft)}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-amber-400/15 px-3 py-2 text-[12.5px] font-semibold text-amber-100 ring-1 ring-amber-300/30 transition hover:bg-amber-400/25"
          >
            <RefreshCcw className="h-3.5 w-3.5" /> Apply changes & re-render
          </button>
        </div>
      )}
    </div>
  );
}

function autoDecision(p: PersonInfo, job: Job): boolean {
  const s = job.settings;
  if (s.target === "everyone") return true;
  if (p.child) return false;
  if (p.gender === "female") return true;
  if (p.gender === "male") return false;
  return s.uncertain_policy === "censor";
}

import { useEffect, useState } from "react";
import DataTable, { type Column } from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import StatCard from "../components/StatCard";
import type { ProfileData } from "../types";
import type { Telemetry } from "../useTelemetry";

export default function Profile({ tick }: Telemetry) {
  const [profile, setProfile] = useState<ProfileData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/profile")
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => setProfile(data))
      .catch(() => setProfile(null))
      .finally(() => setLoading(false));
  }, []);

  const healthVerdicts = tick?.health ?? [];
  const coveredCount = healthVerdicts.filter((v) => v.signal !== null).length;
  const coveragePct = healthVerdicts.length > 0 ? Math.round((coveredCount / healthVerdicts.length) * 100) : 0;

  const deviceColumns: Column<NonNullable<ProfileData["devices"]>[number]>[] = [
    {
      key: "canId",
      header: "CAN ID",
      sortable: true,
      width: "80px",
      sortValue: (d) => d.canId ?? -1,
      render: (d) => <span className="numeric">{d.canId ?? "—"}</span>,
    },
    {
      key: "label",
      header: "Device Name",
      sortable: true,
      sortValue: (d) => d.label,
      render: (d) => <span style={{ fontWeight: 600 }}>{d.label}</span>,
    },
    {
      key: "subsystem",
      header: "Subsystem",
      render: (d) => <span style={{ color: "var(--theme-textMuted)" }}>{d.subsystem || "—"}</span>,
    },
    {
      key: "vendor",
      header: "Vendor",
      render: (d) => (
        <span className="pill">{d.vendor || "Unknown"}</span>
      ),
    },
    {
      key: "accurate",
      header: "Verification",
      align: "right",
      render: (d) => (
        <span className="eyebrow" style={{ color: d.accurate ? "var(--theme-green)" : "var(--theme-amberDot)" }}>
          {d.accurate ? "Verified" : "Unverified"}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "28px", padding: "24px" }}>
      <PageHeader
        title="Profile & Signal Coverage"
        subtitle="Team/robot profile metadata, CAN device registry, swerve kinematics, and copilot signal coverage audit"
      />

      {/* Signal Coverage Audit Section */}
      <section className="card" style={{ padding: "18px 20px", display: "flex", flexDirection: "column", gap: "12px" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap" }}>
          <div>
            <span className="eyebrow">Telemetry Coverage</span>
            <h3 style={{ margin: "4px 0 0", fontSize: "16px", fontWeight: 600 }}>
              {coveragePct}% Signal Coverage ({coveredCount} of {healthVerdicts.length} checks active)
            </h3>
          </div>
          <span className="numeric" style={{ fontSize: "28px", fontWeight: 700, color: coveragePct === 100 ? "var(--theme-green)" : "var(--theme-amberDot)" }}>
            {coveragePct}%
          </span>
        </div>
        <div style={{ height: "6px", borderRadius: "3px", background: "var(--theme-track)", overflow: "hidden" }}>
          <div style={{ height: "100%", background: "var(--theme-trackFill)", width: `${coveragePct}%` }} />
        </div>
      </section>

      {loading ? (
        <div className="card" style={{ padding: "32px", textAlign: "center", color: "var(--theme-textMuted)" }}>
          Loading robot profile...
        </div>
      ) : !profile?.loaded ? (
        <div className="card" style={{ padding: "32px 20px", borderStyle: "dashed", textAlign: "center" }}>
          <p style={{ margin: "0 0 6px", fontSize: "13px", fontWeight: 500 }}>No robot profile loaded</p>
          <p style={{ margin: 0, fontSize: "12px", color: "var(--theme-textMuted)" }}>
            Run <code style={{ fontFamily: "var(--mono)" }}>profile bootstrap &lt;repo-path&gt;</code> to auto-generate a profile, then start the dashboard with <code style={{ fontFamily: "var(--mono)" }}>--profile profile.json</code>.
          </p>
        </div>
      ) : (
        <>
          {/* Hero Profile Info */}
          <section className="card" style={{ padding: "20px", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "16px" }}>
            <div>
              <span className="eyebrow">Team {profile.team ?? 6369}</span>
              <h2 style={{ margin: "4px 0 0", fontSize: "20px", fontWeight: 700 }}>
                {profile.robot || "Mercenary Robot"}
              </h2>
              <span style={{ fontSize: "12.5px", color: "var(--theme-textMuted)", marginTop: "4px", display: "block" }}>
                Season {profile.season || 2026} • Game: {profile.game || "REBUILT"}
              </span>
            </div>

            <div style={{ display: "flex", gap: "6px", flexWrap: "wrap" }}>
              {(profile.vendors ?? ["CTRE", "REV"]).map((v) => (
                <span key={v} className="pill" style={{ padding: "4px 10px", fontSize: "12px" }}>
                  {v}
                </span>
              ))}
            </div>
          </section>

          {/* Drivetrain Kinematics Stat Grid */}
          {profile.drivetrain && (
            <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
              <h2 className="eyebrow" style={{ margin: 0 }}>
                Swerve Kinematics & Physical Parameters
              </h2>
              <div className="stat-grid">
                <StatCard label="Mass" value={`${profile.drivetrain.massKg ?? "—"} kg`} subtitle="Total robot weight" />
                <StatCard label="Trackwidth" value={`${profile.drivetrain.trackwidthM ?? "—"} m`} subtitle="Wheelbase width" />
                <StatCard label="Max Speed" value={`${profile.drivetrain.maxSpeedMps ?? "—"} m/s`} subtitle="Theoretical top speed" />
                <StatCard label="Drive Motor" value={profile.drivetrain.driveMotor || "Falcon 500"} subtitle={`Limit: ${profile.drivetrain.driveCurrentLimitA ?? 40}A`} />
              </div>
            </section>
          )}

          {/* CAN Devices Table */}
          {profile.devices && profile.devices.length > 0 && (
            <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
              <h2 className="eyebrow" style={{ margin: 0 }}>
                CAN Device Inventory ({profile.devices.length})
              </h2>
              <DataTable columns={deviceColumns} data={profile.devices} keyExtractor={(d, i) => `${d.canId}-${i}`} />
            </section>
          )}

          {/* Mechanisms & Subsystems */}
          {profile.mechanisms && profile.mechanisms.length > 0 && (
            <section style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
              <h2 className="eyebrow" style={{ margin: 0 }}>
                Mechanism Clearance & Degrees of Freedom
              </h2>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: "10px" }}>
                {profile.mechanisms.map((m) => (
                  <div key={m.name} className="card" style={{ padding: "14px", display: "flex", flexDirection: "column", gap: "6px" }}>
                    <span style={{ fontWeight: 600, fontSize: "13px" }}>{m.name}</span>
                    <span className="eyebrow" style={{ color: "var(--theme-textMuted)" }}>
                      Type: {m.type || "Articulated"} • DOF: {m.degreesOfFreedom ?? 1}
                    </span>
                    {m.maxHeightMeters && (
                      <span style={{ fontSize: "12px", color: "var(--accent)", marginTop: "2px" }}>
                        Max Height: {m.maxHeightMeters}m
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}

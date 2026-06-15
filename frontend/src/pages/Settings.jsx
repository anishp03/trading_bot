import { useCallback, useEffect, useState } from "react";
import { apiFetch, apiFormFetch } from "../utils/api.js";

const PROVIDER_LABELS = {
  TRADOVATE: "Tradovate",
  TOPSTEPX: "TopstepX",
};

export default function Settings({ accountEmail }) {
  const [accountSettings, setAccountSettings] = useState({
    name: "",
    email: "",
    phoneNumber: "",
    address: "",
  });
  const [connections, setConnections] = useState([]);
  const [futuresFeedback, setFuturesFeedback] = useState("");
  const [busyProvider, setBusyProvider] = useState("");
  const [topstepAccountDraft, setTopstepAccountDraft] = useState({ name: "", accountId: "" });

  const loadAccountSettings = useCallback((emailToLoad) => {
    apiFetch(`/api/settings/account?email=${encodeURIComponent(emailToLoad)}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load account settings.");
        }

        return response.json();
      })
      .then((data) => {
        setAccountSettings(data);
      })
      .catch((error) => {
        console.error("Error loading account settings:", error);
        setAccountSettings({
          name: "",
          email: emailToLoad || "",
          phoneNumber: "",
          address: "",
        });
      });
  }, []);

  const loadFuturesConnections = useCallback(() => {
    apiFetch("/api/futures/connections")
      .then((response) => response.json())
      .then((data) => setConnections(Array.isArray(data) ? data : []))
      .catch((error) => {
        console.error("Error loading futures connections:", error);
        setConnections([]);
      });
  }, []);

  useEffect(() => {
    loadAccountSettings(accountEmail);
    loadFuturesConnections();
  }, [accountEmail, loadAccountSettings, loadFuturesConnections]);

  function updateFuturesConnection(provider, field, value) {
    setConnections((current) =>
      current.map((connection) => (connection.provider === provider ? { ...connection, [field]: value } : connection))
    );
  }

  async function saveFuturesConnection(connection) {
    setBusyProvider(connection.provider);
    setFuturesFeedback("");

    const params = {
      enabled: String(Boolean(connection.enabled)),
      baseUrl: connection.baseUrl || "",
      environment: connection.environment || "",
      username: connection.username || "",
      apiKey: connection.apiKey || "__KEEP__",
      password: connection.password || "__KEEP__",
      secret: connection.secret || "__KEEP__",
      appId: connection.appId || "",
      appVersion: connection.appVersion || "",
      cid: connection.cid || "",
      accountId: connection.accountId || "",
      accountSpec: connection.accountSpec || "",
      dataset: connection.dataset || "",
      schema: connection.schema || "",
      symbols: connection.symbols || "",
      marketHubUrl: connection.marketHubUrl || "",
      userHubUrl: connection.userHubUrl || "",
    };

    try {
      const response = await apiFormFetch(`/api/futures/connections/${connection.provider}`, params);
      const payload = await readApiResponse(response);
      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Failed to save futures connection.");
      }
      setConnections(Array.isArray(payload.json) ? payload.json : []);
      setFuturesFeedback(`${providerLabel(connection.provider)} connection saved.`);
    } catch (error) {
      console.error("Error saving futures connection:", error);
      setFuturesFeedback(error.message || "Failed to save futures connection.");
    } finally {
      setBusyProvider("");
    }
  }

  async function testFuturesConnection(provider) {
    setBusyProvider(provider);
    setFuturesFeedback("");

    try {
      const response = await apiFetch(`/api/futures/connections/${provider}/test`, { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok) {
        throw new Error(payload.message || payload.text || "Connection test failed.");
      }
      setFuturesFeedback(payload.json?.message || "Connection test completed.");
      loadFuturesConnections();
    } catch (error) {
      console.error("Error testing futures connection:", error);
      setFuturesFeedback(error.message || "Connection test failed.");
    } finally {
      setBusyProvider("");
    }
  }

  function updateTopstepAccountDraft(field, value) {
    setTopstepAccountDraft((current) => ({ ...current, [field]: value }));
  }

  async function saveTopstepAccount() {
    setBusyProvider("TOPSTEPX_ACCOUNT");
    setFuturesFeedback("");

    try {
      const response = await apiFormFetch("/api/futures/topstepx/accounts", {
        name: topstepAccountDraft.name || "",
        accountId: topstepAccountDraft.accountId || "",
        activate: "true",
      });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to save Topstep account.");
      }
      setTopstepAccountDraft({ name: "", accountId: "" });
      setFuturesFeedback(payload.json?.message || "Topstep account saved.");
      loadFuturesConnections();
    } catch (error) {
      console.error("Error saving Topstep account:", error);
      setFuturesFeedback(error.message || "Failed to save Topstep account.");
    } finally {
      setBusyProvider("");
    }
  }

  async function refreshTopstepAccounts() {
    setBusyProvider("TOPSTEPX_ACCOUNT_REFRESH");
    setFuturesFeedback("");

    try {
      const response = await apiFetch("/api/futures/topstepx/accounts/refresh", { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to refresh Topstep accounts.");
      }
      setFuturesFeedback(payload.json?.message || "Topstep accounts refreshed.");
      loadFuturesConnections();
    } catch (error) {
      console.error("Error refreshing Topstep accounts:", error);
      setFuturesFeedback(error.message || "Failed to refresh Topstep accounts.");
    } finally {
      setBusyProvider("");
    }
  }

  async function activateTopstepAccount(accountId) {
    setBusyProvider(`TOPSTEPX_ACCOUNT_${accountId}`);
    setFuturesFeedback("");

    try {
      const response = await apiFetch(`/api/futures/topstepx/accounts/${encodeURIComponent(accountId)}/activate`, { method: "POST" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to activate Topstep account.");
      }
      setFuturesFeedback(payload.json?.message || "Topstep account activated.");
      loadFuturesConnections();
    } catch (error) {
      console.error("Error activating Topstep account:", error);
      setFuturesFeedback(error.message || "Failed to activate Topstep account.");
    } finally {
      setBusyProvider("");
    }
  }

  async function deleteTopstepAccount(accountId) {
    setBusyProvider(`TOPSTEPX_ACCOUNT_DELETE_${accountId}`);
    setFuturesFeedback("");

    try {
      const response = await apiFetch(`/api/futures/topstepx/accounts/${encodeURIComponent(accountId)}`, { method: "DELETE" });
      const payload = await readApiResponse(response);
      if (!response.ok || payload.json?.success === false) {
        throw new Error(payload.json?.message || payload.text || "Failed to remove Topstep account.");
      }
      setFuturesFeedback(payload.json?.message || "Topstep account removed.");
      loadFuturesConnections();
    } catch (error) {
      console.error("Error removing Topstep account:", error);
      setFuturesFeedback(error.message || "Failed to remove Topstep account.");
    } finally {
      setBusyProvider("");
    }
  }

  return (
    <div className="app-page app-settings-page">
      <h2 className="app-title">Settings</h2>

      <div className="app-panel">
        <div className="fw-bold app-kicker mb-3">Primary Account</div>

        <SettingRow label="Name" value={accountSettings.name} />
        <SettingRow label="Connected Email" value={accountSettings.email || accountEmail} />
        <SettingRow label="Phone Number" value={accountSettings.phoneNumber} />
        <SettingRow label="Address" value={accountSettings.address} />
      </div>

      {futuresFeedback && <div className="app-live-feedback">{futuresFeedback}</div>}

      {connections.map((connection) => (
        <ConnectionPanel
          key={connection.provider}
          busyProvider={busyProvider}
          connection={connection}
          onChange={updateFuturesConnection}
          onSave={saveFuturesConnection}
          onTest={testFuturesConnection}
          topstepAccountDraft={topstepAccountDraft}
          onTopstepDraftChange={updateTopstepAccountDraft}
          onSaveTopstepAccount={saveTopstepAccount}
          onRefreshTopstepAccounts={refreshTopstepAccounts}
          onActivateTopstepAccount={activateTopstepAccount}
          onDeleteTopstepAccount={deleteTopstepAccount}
        />
      ))}

      {connections.length === 0 && <div className="app-panel app-empty">No futures connections found.</div>}
    </div>
  );
}

function ConnectionPanel({
  connection,
  busyProvider,
  onChange,
  onSave,
  onTest,
  topstepAccountDraft,
  onTopstepDraftChange,
  onSaveTopstepAccount,
  onRefreshTopstepAccounts,
  onActivateTopstepAccount,
  onDeleteTopstepAccount,
}) {
  const provider = connection.provider;
  const isBusy = busyProvider === provider;
  const topstepAccounts = Array.isArray(connection.topstepAccounts) ? connection.topstepAccounts : [];
  const savingTopstepAccount = busyProvider === "TOPSTEPX_ACCOUNT";

  return (
    <div className="app-panel">
      <div className="app-service-header">
        <div className="fw-bold app-kicker">{providerLabel(provider)}</div>
        <div className="d-flex gap-2 flex-wrap">
          <span className={connection.lastTestStatus === "connected" ? "app-badge app-positive-badge" : "app-badge"}>
            {connection.lastTestStatus || "not_tested"}
          </span>
          <label className="app-toggle-row">
            <input
              type="checkbox"
              checked={Boolean(connection.enabled)}
              onChange={(event) => onChange(provider, "enabled", event.target.checked)}
            />
            <span>Enabled</span>
          </label>
        </div>
      </div>

      <div className="row g-3 mt-1">
        <Field label="Base URL" className="col-12 col-xl-4">
          <input
            value={connection.baseUrl || ""}
            onChange={(event) => onChange(provider, "baseUrl", event.target.value)}
            className="form-control app-input"
          />
        </Field>

        <Field label={provider === "TOPSTEPX" ? "Account Mode" : "Environment"} className="col-12 col-md-4 col-xl-2">
          <input
            value={connection.environment || ""}
            onChange={(event) => onChange(provider, "environment", event.target.value)}
            placeholder={provider === "TOPSTEPX" ? "Practice / Combine" : ""}
            className="form-control app-input"
          />
        </Field>

        <Field label={credentialFieldLabel(provider, "username")} className="col-12 col-md-4 col-xl-3">
          <input
            type="text"
            value={connection.username || ""}
            onChange={(event) => onChange(provider, "username", event.target.value)}
            placeholder={provider === "TOPSTEPX" ? "ProjectX username" : ""}
            className="form-control app-input"
          />
        </Field>

        <Field label={credentialFieldLabel(provider, "apiKey")} className="col-12 col-md-4 col-xl-3">
          <input
            type="password"
            value={provider === "TOPSTEPX" ? connection.apiKey || "" : connection.password || ""}
            onChange={(event) => onChange(provider, provider === "TOPSTEPX" ? "apiKey" : "password", event.target.value)}
            placeholder={provider === "TOPSTEPX" ? connection.apiKeyPreview || "TopstepX API key" : connection.hasPassword ? "Saved" : ""}
            className="form-control app-input"
          />
        </Field>

        {provider === "TRADOVATE" && (
          <>
            <Field label="App ID" className="col-12 col-md-4 col-xl-2">
              <input
                value={connection.appId || ""}
                onChange={(event) => onChange(provider, "appId", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="App Version" className="col-12 col-md-4 col-xl-2">
              <input
                value={connection.appVersion || ""}
                onChange={(event) => onChange(provider, "appVersion", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="CID" className="col-12 col-md-4 col-xl-2">
              <input
                value={connection.cid || ""}
                onChange={(event) => onChange(provider, "cid", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="Secret" className="col-12 col-md-4 col-xl-3">
              <input
                type="password"
                value={connection.secret || ""}
                onChange={(event) => onChange(provider, "secret", event.target.value)}
                placeholder={connection.secretPreview || ""}
                className="form-control app-input"
              />
            </Field>

            <Field label="Account Spec" className="col-12 col-md-4 col-xl-3">
              <input
                value={connection.accountSpec || ""}
                onChange={(event) => onChange(provider, "accountSpec", event.target.value)}
                className="form-control app-input"
              />
            </Field>
          </>
        )}

        <Field label={provider === "TOPSTEPX" ? "Topstep Account ID" : "Account ID"} className="col-12 col-md-4 col-xl-2">
          <input
            value={connection.accountId || ""}
            onChange={(event) => onChange(provider, "accountId", event.target.value)}
            readOnly={provider === "TOPSTEPX"}
            className="form-control app-input"
          />
        </Field>

        {provider === "TOPSTEPX" && (
          <>
            <Field label="Market Hub" className="col-12 col-xl-5">
              <input
                value={connection.marketHubUrl || ""}
                onChange={(event) => onChange(provider, "marketHubUrl", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="User Hub" className="col-12 col-xl-5">
              <input
                value={connection.userHubUrl || ""}
                onChange={(event) => onChange(provider, "userHubUrl", event.target.value)}
                className="form-control app-input"
              />
            </Field>
          </>
        )}

        {provider === "TOPSTEPX" && (
          <TopstepAccountsPanel
            accounts={topstepAccounts}
            activeAccountId={connection.accountId || ""}
            draft={topstepAccountDraft}
            saving={savingTopstepAccount}
            busyProvider={busyProvider}
            onDraftChange={onTopstepDraftChange}
            onSave={onSaveTopstepAccount}
            onRefresh={onRefreshTopstepAccounts}
            onActivate={onActivateTopstepAccount}
            onDelete={onDeleteTopstepAccount}
          />
        )}

        <div className="col-12 d-flex align-items-center justify-content-between gap-2 flex-wrap">
          <div className="app-muted app-kicker">
            {connection.lastTestMessage || "Not tested yet."}
          </div>
          <div className="d-flex gap-2">
            <button type="button" className="app-btn px-3" onClick={() => onTest(provider)} disabled={isBusy}>
              {isBusy ? "Working..." : "Test Connection"}
            </button>
            <button type="button" className="app-btn app-btn-primary px-3" onClick={() => onSave(connection)} disabled={isBusy}>
              Save Connection
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function TopstepAccountsPanel({ accounts, activeAccountId, draft, saving, busyProvider, onDraftChange, onSave, onRefresh, onActivate, onDelete }) {
  const cleanActiveAccountId = String(activeAccountId || "").trim();
  const refreshing = busyProvider === "TOPSTEPX_ACCOUNT_REFRESH";

  return (
    <div className="col-12 topstep-accounts-section">
      <div className="topstep-accounts-head">
        <div className="fw-bold app-kicker">Topstep Accounts</div>
        <button type="button" className="app-btn app-btn-small px-3" onClick={onRefresh} disabled={refreshing}>
          {refreshing ? "Refreshing..." : "Refresh Topstep Accounts"}
        </button>
      </div>

      <div className="row g-3 align-items-end">
        <Field label="Account Name" className="col-12 col-md-5">
          <input
            value={draft.name || ""}
            onChange={(event) => onDraftChange("name", event.target.value)}
            placeholder="Express Funded"
            className="form-control app-input"
          />
        </Field>
        <Field label="Account ID" className="col-12 col-md-4">
          <input
            value={draft.accountId || ""}
            onChange={(event) => onDraftChange("accountId", event.target.value)}
            inputMode="numeric"
            placeholder="22529998"
            className="form-control app-input"
          />
        </Field>
        <div className="col-12 col-md-3">
          <button type="button" className="app-btn app-btn-primary app-btn-block px-3" onClick={onSave} disabled={saving}>
            {saving ? "Saving..." : "Add Account"}
          </button>
        </div>
      </div>

      <div className="topstep-accounts-list">
        {accounts.map((account) => {
          const accountId = String(account.accountId || "").trim();
          const active = account.active || accountId === cleanActiveAccountId;
          const activating = busyProvider === `TOPSTEPX_ACCOUNT_${accountId}`;
          const deleting = busyProvider === `TOPSTEPX_ACCOUNT_DELETE_${accountId}`;
          return (
            <div className="topstep-account-row" key={accountId || account.name}>
              <div>
                <div className="topstep-account-name">{account.name || "Topstep Account"}</div>
              </div>
              <div className="d-flex gap-2 align-items-center flex-wrap justify-content-end">
                {active && <span className="app-badge app-positive-badge">active</span>}
                <button
                  type="button"
                  className="app-btn app-btn-small px-3"
                  onClick={() => onActivate(accountId)}
                  disabled={active || activating || !accountId}
                >
                  {activating ? "Switching..." : "Use"}
                </button>
                <button
                  type="button"
                  className="app-btn app-btn-danger app-btn-small px-3"
                  onClick={() => onDelete(accountId)}
                  disabled={deleting || !accountId}
                >
                  {deleting ? "Removing..." : "Remove"}
                </button>
              </div>
            </div>
          );
        })}
        {accounts.length === 0 && <div className="app-muted app-kicker">No saved Topstep accounts.</div>}
      </div>
    </div>
  );
}

function Field({ label, children, className = "col" }) {
  return (
    <div className={className}>
      <label className="d-grid gap-1">
        <span className="app-label">{label}</span>
        {children}
      </label>
    </div>
  );
}

async function readApiResponse(response) {
  const text = await response.text();
  if (!text) {
    return { json: null, text: "" };
  }

  try {
    return { json: JSON.parse(text), text };
  } catch {
    return { json: null, text };
  }
}

function providerLabel(provider) {
  return PROVIDER_LABELS[provider] || provider || "Connection";
}

function credentialFieldLabel(provider, field) {
  if (provider === "TOPSTEPX" && field === "username") return "ProjectX Username";
  if (provider === "TOPSTEPX" && field === "apiKey") return "ProjectX API Key";
  return field === "apiKey" ? "Password" : "Username";
}

function SettingRow({ label, value, concealed = false, revealed = false, onToggleReveal }) {
  const hasValue = Boolean(value);
  const displayValue = concealed && hasValue && !revealed ? maskSettingValue(value) : (value || "Not set");

  return (
    <div className="app-settings-row">
      <div className="app-label">{label}</div>
      <div className="app-settings-value-row">
        <div className="app-settings-value">{displayValue}</div>
        {concealed && hasValue && (
          <button type="button" className="app-btn px-3 app-settings-action" onClick={onToggleReveal}>
            {revealed ? "Hide" : "Reveal"}
          </button>
        )}
      </div>
    </div>
  );
}

function maskSettingValue(value) {
  const safeValue = String(value || "");

  if (!safeValue) {
    return "Not set";
  }

  return "*".repeat(Math.min(Math.max(safeValue.length, 8), 18));
}

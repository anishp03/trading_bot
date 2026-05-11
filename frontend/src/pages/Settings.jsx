import { useCallback, useEffect, useState } from "react";
import { apiFetch, apiFormFetch } from "../utils/api.js";

const PROVIDER_LABELS = {
  DATABENTO: "Databento",
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
  const [brokerSettings, setBrokerSettings] = useState({
    broker: "Alpaca",
    baseUrl: "https://paper-api.alpaca.markets/v2",
    connectedAccountName: "Not connected",
    hasApiKey: false,
    apiKeyPreview: "",
    hasSecretKey: false,
    secretKeyPreview: "",
  });
  const [isBrokerOpen, setIsBrokerOpen] = useState(false);
  const [brokerDraft, setBrokerDraft] = useState({ apiKey: "", secretKey: "" });
  const [brokerError, setBrokerError] = useState("");
  const [connections, setConnections] = useState([]);
  const [futuresFeedback, setFuturesFeedback] = useState("");
  const [busyProvider, setBusyProvider] = useState("");

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

  const loadBrokerSettings = useCallback((emailToLoad) => {
    apiFetch(`/api/settings/broker?email=${encodeURIComponent(emailToLoad)}`)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Failed to load broker settings.");
        }

        return response.json();
      })
      .then((data) => {
        setBrokerSettings(data);
      })
      .catch((error) => {
        console.error("Error loading broker settings:", error);
        setBrokerSettings((current) => ({
          ...current,
          connectedAccountName: "Unavailable",
          hasApiKey: false,
          apiKeyPreview: "",
          hasSecretKey: false,
          secretKeyPreview: "",
        }));
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
    loadBrokerSettings(accountEmail);
    loadFuturesConnections();
  }, [accountEmail, loadAccountSettings, loadBrokerSettings, loadFuturesConnections]);

  function openBrokerModal() {
    setBrokerDraft({
      apiKey: "",
      secretKey: "",
    });
    setBrokerError("");
    setIsBrokerOpen(true);
  }

  function saveBrokerSettings(event) {
    event.preventDefault();
    setBrokerError("");

    apiFormFetch("/api/settings/broker", {
      email: accountEmail || "",
      apiKey: brokerDraft.apiKey,
      secretKey: brokerDraft.secretKey,
    })
      .then(async (response) => {
        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || "Broker settings update failed.");
        }

        loadBrokerSettings(accountEmail);
        setIsBrokerOpen(false);
        setBrokerDraft({ apiKey: "", secretKey: "" });
      })
      .catch((error) => {
        setBrokerError(error.message || "Broker settings update failed.");
      });
  }

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

      <div className="app-panel">
        <div className="app-service-header mb-3">
          <div className="fw-bold app-kicker">Alpaca</div>
          <span className="app-badge">{brokerSettings.connectedAccountName || "Not connected"}</span>
        </div>

        <SettingRow label="Current Broker" value={brokerSettings.broker} />
        <SettingRow label="API URL" value={brokerSettings.baseUrl} />
        <SettingRow label="Connected Account Name" value={brokerSettings.connectedAccountName} />
        <SettingRow
          label="API Key"
          value={brokerSettings.hasApiKey ? brokerSettings.apiKeyPreview || "Saved" : ""}
        />
        <SettingRow
          label="Secret Key"
          value={brokerSettings.hasSecretKey ? brokerSettings.secretKeyPreview || "Saved" : ""}
        />

        <div className="d-flex justify-content-end pt-3">
          <button type="button" className="app-btn app-btn-primary px-3" onClick={openBrokerModal}>
            Change Broker Account
          </button>
        </div>
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
        />
      ))}

      {connections.length === 0 && <div className="app-panel app-empty">No futures connections found.</div>}

      {isBrokerOpen && (
        <SettingsModal title="Change Broker Account" onClose={() => setIsBrokerOpen(false)}>
          <form onSubmit={saveBrokerSettings} className="d-grid gap-3">
            <label className="d-grid gap-1">
              <span className="app-label">Broker</span>
              <input type="text" className="form-control app-input" value={brokerSettings.broker} disabled />
            </label>

            <label className="d-grid gap-1">
              <span className="app-label">API URL</span>
              <input type="text" className="form-control app-input" value={brokerSettings.baseUrl} disabled />
            </label>

            <label className="d-grid gap-1">
              <span className="app-label">API Key</span>
              <input
                type="password"
                autoComplete="off"
                className="form-control app-input"
                value={brokerDraft.apiKey}
                onChange={(event) => setBrokerDraft((current) => ({ ...current, apiKey: event.target.value }))}
                placeholder={brokerSettings.hasApiKey ? "Saved; enter a new key to replace" : "Enter Alpaca API key"}
              />
            </label>

            <label className="d-grid gap-1">
              <span className="app-label">Secret Key</span>
              <input
                type="password"
                autoComplete="off"
                className="form-control app-input"
                value={brokerDraft.secretKey}
                onChange={(event) => setBrokerDraft((current) => ({ ...current, secretKey: event.target.value }))}
                placeholder={brokerSettings.hasSecretKey ? "Saved; enter a new secret to replace" : "Enter Alpaca secret key"}
              />
            </label>

            {brokerError && <div className="app-pnl-neg">{brokerError}</div>}

            <div className="d-flex justify-content-end gap-2">
              <button type="button" className="app-btn px-3" onClick={() => setIsBrokerOpen(false)}>
                Cancel
              </button>
              <button type="submit" className="app-btn app-btn-primary px-3">
                Save
              </button>
            </div>
          </form>
        </SettingsModal>
      )}
    </div>
  );
}

function ConnectionPanel({ connection, busyProvider, onChange, onSave, onTest }) {
  const provider = connection.provider;
  const isBusy = busyProvider === provider;
  const hasSecretFields = provider !== "DATABENTO";

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
            type={provider === "DATABENTO" ? "password" : "text"}
            autoComplete={provider === "DATABENTO" ? "off" : undefined}
            value={provider === "DATABENTO" ? connection.apiKey || "" : connection.username || ""}
            onChange={(event) => onChange(provider, provider === "DATABENTO" ? "apiKey" : "username", event.target.value)}
            placeholder={provider === "DATABENTO" ? connection.apiKeyPreview || "db-..." : provider === "TOPSTEPX" ? "ProjectX username" : ""}
            className="form-control app-input"
          />
        </Field>

        {provider !== "DATABENTO" && (
          <Field label={credentialFieldLabel(provider, "apiKey")} className="col-12 col-md-4 col-xl-3">
            <input
              type="password"
              value={provider === "TOPSTEPX" ? connection.apiKey || "" : connection.password || ""}
              onChange={(event) => onChange(provider, provider === "TOPSTEPX" ? "apiKey" : "password", event.target.value)}
              placeholder={provider === "TOPSTEPX" ? connection.apiKeyPreview || "TopstepX API key" : connection.hasPassword ? "Saved" : ""}
              className="form-control app-input"
            />
          </Field>
        )}

        {hasSecretFields && provider === "TRADOVATE" && (
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

        {provider !== "DATABENTO" && (
          <Field label={provider === "TOPSTEPX" ? "Topstep Account ID" : "Account ID"} className="col-12 col-md-4 col-xl-2">
            <input
              value={connection.accountId || ""}
              onChange={(event) => onChange(provider, "accountId", event.target.value)}
              className="form-control app-input"
            />
          </Field>
        )}

        {provider === "DATABENTO" && (
          <>
            <Field label="Dataset" className="col-12 col-md-4 col-xl-2">
              <input
                value={connection.dataset || ""}
                onChange={(event) => onChange(provider, "dataset", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="Schema" className="col-12 col-md-4 col-xl-2">
              <input
                value={connection.schema || ""}
                onChange={(event) => onChange(provider, "schema", event.target.value)}
                className="form-control app-input"
              />
            </Field>

            <Field label="Continuous Symbols" className="col-12 col-xl-4">
              <input
                value={connection.symbols || ""}
                onChange={(event) => onChange(provider, "symbols", event.target.value)}
                className="form-control app-input"
              />
            </Field>
          </>
        )}

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

        <div className="col-12 d-flex align-items-center justify-content-between gap-2 flex-wrap">
          <div className="app-muted app-kicker">
            {connection.lastTestMessage || "Not tested yet."}
          </div>
          <div className="d-flex gap-2">
            <button type="button" className="app-btn px-3" onClick={() => onTest(provider)} disabled={isBusy}>
              {isBusy ? "Working..." : "Test"}
            </button>
            <button type="button" className="app-btn app-btn-primary px-3" onClick={() => onSave(connection)} disabled={isBusy}>
              Save
            </button>
          </div>
        </div>
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
  if (provider === "DATABENTO") return "API Key";
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

function SettingsModal({ title, onClose, children }) {
  return (
    <div className="app-modal-backdrop">
      <div className="app-modal-card">
        <div className="d-flex align-items-center justify-content-between gap-2 mb-3">
          <div className="fw-bold">{title}</div>
          <button type="button" className="app-btn px-3" onClick={onClose}>
            Close
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

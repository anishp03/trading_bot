import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFormFetch, readApiErrorMessage } from "../utils/api.js";

export default function Login({ onLogin }) {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  function handleLogin(event) {
    event.preventDefault();
    if (isSubmitting) {
      return;
    }

    const normalizedEmail = email.trim().toLowerCase();

    setError("");
    setEmail(normalizedEmail);
    setIsSubmitting(true);

    apiFormFetch("/api/login", { email: normalizedEmail, password })
      .then(async (response) => {
        if (response.ok) {
          const session = await response.json();
          onLogin(session);
          navigate("/dashboard", { replace: true });
        } else {
          setError(await readApiErrorMessage(response, "Invalid credentials."));
        }
      })
      .catch((requestError) => {
        console.error(requestError);
        setError("Failed to connect to the server.");
      })
      .finally(() => {
        setIsSubmitting(false);
      });
  }

  function handleFormKeyDown(event) {
    if (event.key !== "Enter" || event.isComposing || isSubmitting) {
      return;
    }

    if (event.target?.tagName === "BUTTON") {
      return;
    }

    event.preventDefault();
    event.currentTarget.requestSubmit();
  }

  return (
    <div
      className="auth-page"
    >
      <div className="app-panel auth-panel">
        <h2 className="app-title mb-4 text-center">Trading Bot</h2>

        {error && (
          <div className="mb-3 text-center fw-bold" style={{ color: "var(--app-negative)" }}>
            {error}
          </div>
        )}

        <form onSubmit={handleLogin} onKeyDown={handleFormKeyDown} className="d-grid gap-3">
          <label className="d-grid gap-1">
            <span className="app-label">Email</span>
            <input
              type="email"
              className="form-control app-input"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              autoFocus
              required
            />
          </label>

          <label className="d-grid gap-1">
            <span className="app-label">Password</span>
            <input
              type="password"
              className="form-control app-input"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          <div className="d-grid gap-2 mt-2">
            <button type="submit" className="app-btn app-btn-primary fw-bold" disabled={isSubmitting}>
              {isSubmitting ? "Logging in..." : "Login"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

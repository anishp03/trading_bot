import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.jsx";
import "./index.css";

class RootErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error("Frontend render failed:", error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="app-boot-error">
          <h1>Frontend failed to start.</h1>
          <p>{this.state.error?.message || "Unknown render error."}</p>
        </div>
      );
    }

    return this.props.children;
  }
}

function showBootError(error) {
  const root = document.getElementById("root");
  if (!root || root.childElementCount > 0) {
    return;
  }

  const message = error?.message || String(error || "Unknown boot error.");
  root.innerHTML = `
    <div class="app-boot-error">
      <h1>Frontend failed to start.</h1>
      <p>${message.replace(/[&<>"']/g, (char) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      }[char]))}</p>
    </div>
  `;
}

window.addEventListener("error", (event) => showBootError(event.error || event.message));
window.addEventListener("unhandledrejection", (event) => showBootError(event.reason));

const rootElement = document.getElementById("root");

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <BrowserRouter>
      <RootErrorBoundary>
        <App />
      </RootErrorBoundary>
    </BrowserRouter>
  </React.StrictMode>
);

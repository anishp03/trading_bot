import { Link } from "react-router-dom";

export default function NotFound() {
  return (
    <div className="app-page">
      <h2 className="app-title">404</h2>
      <div className="app-muted">That page doesn’t exist.</div>
      <Link to="/dashboard" className="text-light">
        Go to Live Stock
      </Link>
    </div>
  );
}

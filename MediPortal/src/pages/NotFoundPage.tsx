import { Link, useLocation } from 'react-router'

function NotFoundPage() {
  const location = useLocation()

  return (
    <section className="page" aria-labelledby="not-found-title">
      <p className="page__eyebrow">Error 404</p>
      <h1 id="not-found-title">Page not found</h1>
      <p className="page__lead">
        The page <code>{location.pathname}</code> does not exist.
      </p>
      <p>
        <Link to="/" className="button-link">
          Go back to the home page
        </Link>
      </p>
    </section>
  )
}

export default NotFoundPage

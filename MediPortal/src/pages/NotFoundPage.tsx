import { Compass, House } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import Card from '../components/ui/Card.tsx'
import PageHeader from '../components/ui/PageHeader.tsx'

function NotFoundPage() {
  const location = useLocation()

  return (
    <section className="page page--narrow" aria-labelledby="not-found-title">
      <PageHeader titleId="not-found-title" eyebrow="Error 404" title="Page not found" icon={Compass} />
      <Card>
        <p className="muted">
          The page <code>{location.pathname}</code> does not exist.
        </p>
        <Link to="/" className="btn btn--primary"><House size={18} aria-hidden="true" />Go back to the home page</Link>
      </Card>
    </section>
  )
}

export default NotFoundPage

import BackendStatus from '../components/ui/BackendStatus.tsx'

function HomePage() {
  return (
    <section className="page" aria-labelledby="home-title">
      <h1 id="home-title">MediNexus</h1>
      <p className="page__lead">
        MediNexus is a healthcare journal system that brings patient journals
        and medical records together in one secure place for patients and
        healthcare professionals.
      </p>
      <p className="status">
        <span className="status__indicator" aria-hidden="true" />
        The MediPortal frontend is initialized and ready for feature
        development.
      </p>
      <BackendStatus />
    </section>
  )
}

export default HomePage

import { NavLink } from 'react-router'

function AppNavigation() {
  return (
    <nav className="app-nav" aria-label="Main">
      <ul className="app-nav__list">
        <li>
          <NavLink to="/" end className="app-nav__link">
            Home
          </NavLink>
        </li>
      </ul>
    </nav>
  )
}

export default AppNavigation

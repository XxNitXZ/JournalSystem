import AppRoutes from './app/AppRoutes.tsx'
import './App.css'
import AuthProvider from './features/auth/AuthProvider.tsx'

function App() {
  return <AuthProvider><AppRoutes /></AuthProvider>
}

export default App

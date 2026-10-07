import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext.jsx'
import Layout from './components/Layout.jsx'
import IncidentListPage from './pages/IncidentListPage.jsx'
import IncidentRoomPage from './pages/IncidentRoomPage.jsx'
import LoginPage from './pages/LoginPage.jsx'

function RequireAuth({ children }) {
  const { session } = useAuth()
  return session ? children : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/" element={<IncidentListPage />} />
        <Route path="/incidents/:id" element={<IncidentRoomPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

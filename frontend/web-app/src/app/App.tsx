import { Route, Routes } from 'react-router-dom';
import { SELLER_ROLES, STAFF_ROLES } from '../auth/claims';
import { RequireAccess } from './Guards';
import { Layout } from './Layout';
import { CallbackPage, HomePage, NotFoundPage, SellerHome, StaffHome, WorkEntry } from './pages';

/**
 * Маршруты. Разделы `/seller/*`, `/staff/*` и `/work` открываются входом через клиент `dgm-staff` со вторым фактором,
 * остальное через `dgm-web` (clientKindForPath). Экраны витрины, кабинета и модерации добавляют шаги 17–19.
 */
export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="oidc/callback/:kind" element={<CallbackPage />} />
        <Route
          path="work"
          element={
            <RequireAccess kind="staff" roles={[...SELLER_ROLES, ...STAFF_ROLES]}>
              <WorkEntry />
            </RequireAccess>
          }
        />
        <Route
          path="seller/*"
          element={
            <RequireAccess kind="staff" roles={SELLER_ROLES} secondFactor>
              <SellerHome />
            </RequireAccess>
          }
        />
        <Route
          path="staff/*"
          element={
            <RequireAccess kind="staff" roles={STAFF_ROLES} secondFactor>
              <StaffHome />
            </RequireAccess>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

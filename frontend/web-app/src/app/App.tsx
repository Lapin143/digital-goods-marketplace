import { Outlet, Route, Routes } from 'react-router-dom';
import { SELLER_ROLES, STAFF_ROLES } from '../auth/claims';
import { RequireAccess } from './Guards';
import { Layout } from './Layout';
import { CallbackPage, HomePage, NotFoundPage, SellerHome, StaffHome, WorkEntry } from './pages';
import { EmailHelpPage } from '../screens/account/EmailHelpPage';
import { PhonePage } from '../screens/account/PhonePage';
import { SmsHome } from '../screens/account/SmsHome';
import { ApplicationPage } from '../screens/seller/ApplicationPage';
import { KeysPage } from '../screens/seller/KeysPage';
import { SellerProductPage } from '../screens/seller/SellerProductPage';
import { SellerProductsPage } from '../screens/seller/SellerProductsPage';
import { ProductListPage } from '../screens/storefront/ProductListPage';
import { ProductPage } from '../screens/storefront/ProductPage';

/**
 * Маршруты. Разделы `/seller/*`, `/staff/*` и `/work` открываются входом через клиент `dgm-staff` со вторым фактором,
 * остальное через `dgm-web` (clientKindForPath); `/sms` это ограниченная сессия по коду из SMS. Экраны витрины и аккаунта добавил шаг 17,
 * кабинет и модерацию добавят шаги 18 и 19.
 */
export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="products" element={<ProductListPage />} />
        <Route path="products/:productId" element={<ProductPage />} />
        <Route
          path="account/phone"
          element={
            <RequireAccess kind="web" roles={['buyer']}>
              <PhonePage />
            </RequireAccess>
          }
        />
        <Route path="help/email" element={<EmailHelpPage />} />
        <Route
          path="sms/*"
          element={
            <RequireAccess kind="sms" roles={['buyer']}>
              <SmsHome />
            </RequireAccess>
          }
        />
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
          path="apply"
          element={
            <RequireAccess kind="web" roles={['buyer', ...SELLER_ROLES]}>
              <ApplicationPage />
            </RequireAccess>
          }
        />
        <Route
          path="seller"
          element={
            <RequireAccess kind="staff" roles={SELLER_ROLES} secondFactor>
              <Outlet />
            </RequireAccess>
          }
        >
          <Route index element={<SellerHome />} />
          <Route path="products" element={<SellerProductsPage />} />
          <Route path="products/new" element={<SellerProductPage />} />
          <Route path="products/:productId" element={<SellerProductPage />} />
          <Route path="products/:productId/keys" element={<KeysPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
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

package dgm.fixtures;

import jakarta.servlet.http.HttpSession;

/** Нарушение: состояние пользователя в сессии. */
public final class SessionUser {

    public Object read(HttpSession session) {
        return session.getAttribute("cart");
    }
}

package dgm.platform.identity.domain;

import java.util.List;
import java.util.Optional;

/** Страница учётных записей и позиция следующей (пусто на последней странице). */
public record UserPage(List<UserAccount> items, int limit, Optional<UserFilter.After> next) {
}

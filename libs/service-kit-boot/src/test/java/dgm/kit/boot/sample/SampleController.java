package dgm.kit.boot.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Образец контроллера для проверки сверки маршрутов (RouteCoverageTest): не часть каркаса. */
@RestController
@RequestMapping("/api/v1")
class SampleController {

    @GetMapping("/things")
    String list() {
        return "[]";
    }

    @PostMapping("/things/{thingId}/close")
    String close(@PathVariable("thingId") String thingId) {
        return thingId;
    }

    @RequestMapping(path = "/hidden", method = RequestMethod.GET)
    String hidden() {
        return "";
    }
}

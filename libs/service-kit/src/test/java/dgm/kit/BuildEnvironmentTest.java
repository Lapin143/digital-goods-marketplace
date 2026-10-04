package dgm.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildEnvironmentTest {

    @Test
    void testsRunOnJava25() {
        assertEquals(25, Runtime.version().feature());
    }

    @Test
    void moduleNameIsStable() {
        assertEquals("service-kit", ServiceKit.NAME);
    }
}

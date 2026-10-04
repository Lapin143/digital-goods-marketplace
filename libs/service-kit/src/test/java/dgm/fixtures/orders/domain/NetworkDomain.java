package dgm.fixtures.orders.domain;

import java.net.URI;

/** Нарушение: доменный слой знает про сеть. */
public class NetworkDomain {

    public URI address() {
        return URI.create("https://payment.example/charge");
    }

    public java.net.http.HttpClient http() {
        return java.net.http.HttpClient.newHttpClient();
    }
}

# Vertx-Vaadin

Vertx-vaadin projects provides a vert.x verticle that starts a http server and initialize `VertxVaadinService`,
a custom implementation of VaadinService.

VertxVaadinService is inspired from VaadinServletService and takes the same configuration parameters in the form
of a json configuration file and from `@VaadinServletConfiguration` annotation on `VaadinVerticle` subclasses.

All [Servlet Configuration Parameter](https://vaadin.com/docs/-/part/framework/application/application-environment.html#application.environment.parameters)
 can be defined in json file under `vaadin` key.
 
```
{
  "vaadin": {
    "ui": "com.github.mcollovati.vertx.vaadin.sample.SimpleUI",
    "heartbeatInterval": 500,
    "UIProvider": "com.ex.my.MyUIProvider"
  }
}
``` 

Vertx-Vaadin supports PUSH using a custom implementation based on SockJS that replaces the atmosphere stack on client and server side; for this reason widgetset compilation is needed for projects using vertx-vaadin 
 

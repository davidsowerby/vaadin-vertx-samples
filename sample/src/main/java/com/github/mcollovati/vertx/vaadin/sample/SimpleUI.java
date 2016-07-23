package com.github.mcollovati.vertx.vaadin.sample;

import com.github.mcollovati.vertx.vaadin.VertxVaadinRequest;
import com.vaadin.annotations.Theme;
import com.vaadin.annotations.Title;
import com.vaadin.server.VaadinRequest;
import com.vaadin.ui.Label;
import com.vaadin.ui.UI;
import com.vaadin.ui.themes.ValoTheme;
import org.vaadin.viritin.label.Header;
import org.vaadin.viritin.layouts.MVerticalLayout;

/**
 * Created by marco on 21/07/16.
 */
@Theme(ValoTheme.THEME_NAME)
@Title("Vert.x vaadin sample")
public class SimpleUI extends UI {
    @Override
    protected void init(VaadinRequest request) {

        VertxVaadinRequest req = (VertxVaadinRequest)request;


        setContent(new MVerticalLayout(
            new Header("Vert.x Vaadin Sample").setHeaderLevel(1),
            new Label(String.format("Verticle %s deployed on Vert.x",
                req.getService().getVerticle().deploymentID()))
        ).withFullWidth());
    }

}
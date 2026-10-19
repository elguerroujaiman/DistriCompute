module com.districompute {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires com.google.gson;
    requires jbcrypt;
    requires java.prefs;
    requires java.logging;
    requires java.desktop;

    opens com.districompute.client to javafx.graphics;
    opens com.districompute.client.views to javafx.graphics;
    opens com.districompute.client.controllers to javafx.graphics;
    opens com.districompute.client.components to javafx.graphics;
    opens com.districompute.common.models to com.google.gson;
    opens com.districompute.common.utils to java.prefs;
    opens com.districompute.server to com.google.gson;

    exports com.districompute.client;
    exports com.districompute.server;
    exports com.districompute.common.models;
    exports com.districompute.common.patterns;
    exports com.districompute.common.utils;
    exports com.districompute.dao;
    exports com.districompute.network;
}

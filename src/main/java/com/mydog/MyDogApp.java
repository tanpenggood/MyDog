package com.mydog;

import atlantafx.base.theme.CupertinoLight;
import com.mydog.core.AppContext;
import com.mydog.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MyDogApp extends Application {

    private AppContext ctx;
    private Stage stage;

    @Override
    public void init() {
        Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        ctx = AppContext.create();
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("MyDog");
        rebuildScene();
        stage.setWidth(1100);
        stage.setHeight(720);
        stage.show();
    }

    /** Full Scene rebuild — used at startup and after locale switch (no node-patching). */
    public void rebuildScene() {
        Scene scene = MainWindow.build(stage, ctx, this::rebuildScene);
        stage.setScene(scene);
    }

    public AppContext context() {
        return ctx;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

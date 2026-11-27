package com.quizbattle.client;

import com.quizbattle.client.model.GameStatus;
import com.quizbattle.client.service.GameClientService;
import com.quizbattle.client.ui.*;
import com.quizbattle.common.ServerInfo;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Actual window resizing and screenshots; all game fixtures continue through real mock TCP. */
final class GuiResponsiveSmoke implements AutoCloseable {
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private final Stage stage;
    private final UiContext context;
    private final GameClientService service;
    private final GuiMockScenario scenario;
    private final ServerInfo info;
    private final BiConsumer<Boolean, String> check;
    private final GuiJourneySmoke.Capture capture;
    private final Consumer<Throwable> failure;
    private final Runnable finished;
    private final SceneManager scenes;
    private final int[][] sizes = {{1280,800},{960,640},{1366,768},{1920,1080}};
    private final String[] cases = {"welcome","discovery-manual","login","registration","settings","help","loading","error",
            "lobby","create-room","waiting-room","quiz-long-text","answer-submitted","round-result","large-final-standings"};
    private int page;
    private int size;
    private PauseTransition pulse;
    private boolean closed;
    private static final String USER = "campus_player_16";
    GuiResponsiveSmoke(Stage stage, UiContext context, GameClientService service, GuiMockScenario scenario, ServerInfo info,
                       BiConsumer<Boolean,String> check, GuiJourneySmoke.Capture capture, Consumer<Throwable> failure, Runnable finished) throws Exception {
        this.stage=stage; this.context=context; this.service=service; this.scenario=scenario; this.info=info;
        this.check=check; this.capture=capture; this.failure=failure; this.finished=finished;
        context.reducedMotionProperty().set(true); context.selectServer(info);
        scenes=new SceneManager(stage,context); Scene scene=new Scene(scenes.root(),1280,800);
        scene.getStylesheets().addAll(SceneManager.stylesheets()); stage.setScene(scene);
    }
    void start() { later(this::prepare); }
    private void prepare() {
        size=0;
        switch(page) {
            case 0 -> { scenes.navigate(Screen.WELCOME); resize(); }
            case 1 -> { scenes.navigate(Screen.SERVER_SELECTION); resize(); }
            case 2 -> { scenes.navigate(Screen.LOGIN); layout(); ((TextField)scenes.root().lookup("#username")).setText(USER); resize(); }
            case 3 -> { scenes.navigate(Screen.REGISTER); layout(); ((TextField)scenes.root().lookup("#username")).setText(USER); resize(); }
            case 4 -> { scenes.navigate(Screen.SETTINGS); resize(); }
            case 5,6,7 -> { scenes.navigate(Screen.WELCOME); resize(); }
            case 8 -> {
                context.beginAuthentication(); context.invoke(()->service.login(info,USER,new char[]{'x'}))
                        .whenComplete((v,e)->Platform.runLater(()->run(()->{ if(e!=null){throw new AssertionError(e);} await(()->scenes.currentScreen()==Screen.LOBBY && !scenes.loading().isVisible(),this::resize); })));
            }
            case 9 -> { scenes.navigate(Screen.CREATE_ROOM); resize(); }
            case 10 -> {
                context.invoke(()->service.createRoom("Campus Champions Network",3)).whenComplete((v,e)->Platform.runLater(()->run(()->{
                    if(e!=null){throw new AssertionError(e);} await(()->scenes.currentScreen()==Screen.WAITING_ROOM,this::resize);
                })));
            }
            case 11 -> server(scenario::longQuestion,()->await(()->scenes.currentScreen()==Screen.QUIZ && label("questionText").startsWith("A reliable"),this::resize));
            case 12 -> context.invoke(()->service.submitAnswer(service.state().snapshot().question(),1)).whenComplete((v,e)->Platform.runLater(()->run(()->{
                if(e!=null){throw new AssertionError(e);} await(()->service.state().snapshot().status()==GameStatus.ANSWER_SUBMITTED && !scenario.receivedAnswers.isEmpty(),this::resize);
            })));
            case 13 -> server(scenario::finishRound,()->await(()->scenes.currentScreen()==Screen.ROUND_RESULT,this::resize));
            case 14 -> {
                var entries=new ArrayList<String>(); for(int i=0;i<200;i++){entries.add(String.format(Locale.ROOT,"player%03d:%d",i,1000-i));} entries.add(USER+":100");
                server(()->scenario.finalFixture("player000",String.join(";",entries)),()->await(()->scenes.currentScreen()==Screen.FINAL_RESULTS,this::resize));
            }
            default -> finish();
        }
    }
    private String label(String id) { Node n=scenes.root().lookup("#"+id); return n instanceof Label l?l.getText():""; }
    private void resize() {
        scenes.closeHelp(); scenes.loading().hide(); scenes.notifications().dismiss();
        int[] resolution=sizes[size];
        double chromeWidth=stage.getWidth()-stage.getScene().getWidth(), chromeHeight=stage.getHeight()-stage.getScene().getHeight();
        stage.setWidth(resolution[0]+chromeWidth); stage.setHeight(resolution[1]+chromeHeight);
        later(this::inspect);
    }
    private void inspect() throws Exception {
        String id=cases[page]+"-"+sizes[size][0]+"x"+sizes[size][1];
        check.accept(Math.abs(stage.getScene().getWidth()-sizes[size][0])<3 && Math.abs(stage.getScene().getHeight()-sizes[size][1])<3,"Responsive: actual viewport "+id);
        if (size == 0) { keyboardActivation(); }
        decorate(); layout(); checkControls(id);
        if(page==11 || page==12 || page==13){checkAnswerWrapping(id);}
        if(page==14){checkLargeRanking(id);}
        capture.save(scenes.root(),"responsive/"+id+".png");
        if(page==14){
            ScrollPane scroll=(ScrollPane)scenes.root().lookup(".page-scroll");scroll.setVvalue(1);layout();
            // Let the scrollbar-to-end action settle before checking the rendered row.
            later(() -> {
                layout(); Node self = scenes.root().lookup(".ranking-self");
                Node list = scenes.root().lookup("#rankingList");
                Bounds view = list.localToScene(list.getBoundsInLocal());
                Bounds row = self == null ? null : self.localToScene(self.getBoundsInLocal());
                check.accept(row != null && row.getMinY() >= view.getMinY()-2 && row.getMaxY() <= view.getMaxY()+2,
                        "Responsive: last-ranked self is visible and highlighted " + id);
                capture.save(scenes.root(),"responsive/"+id+"-standings.png");scroll.setVvalue(0);finishSize(id);
            });
            return;
        }
        finishSize(id);
    }
    private void finishSize(String id) throws Exception {
        if(page==5){
            var dialog=javafx.stage.Window.getWindows().stream().filter(w->w!=stage&&w.isShowing()).findFirst().orElseThrow();
            check.accept(dialog.getWidth()<=stage.getWidth() && dialog.getHeight()<=stage.getHeight(),"Responsive: help dialog fits "+id);
            capture.save(dialog.getScene().getRoot(),"responsive/help-content-"+sizes[size][0]+"x"+sizes[size][1]+".png");
        }
        scenes.closeHelp(); scenes.loading().hide(); scenes.notifications().dismiss();
        if(++size<sizes.length){resize();}else{stage.setFullScreen(true);later(this::fullscreen);}
    }
    private void checkAnswerWrapping(String id) {
        for(int i=0;i<4;i++){
            Node card=scenes.root().lookup("#answer"+i);
            for(Node child:card.lookupAll(".label")){
                if(child instanceof Label label && label.getText().length()>20 && label.getHeight()+2<label.prefHeight(label.getWidth())){
                    throw new AssertionError("Wrapped answer text is clipped: "+id+" / "+i);
                }
            }
        }
        check.accept(true,"Responsive: long answer text has its full wrapped height "+id);
    }
    private void keyboardActivation() {
        if (page == 2) {
            Button submit = (Button) scenes.root().lookup("#submitButton"); submit.requestFocus();
            submit.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));
            submit.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.ENTER,false,false,false,false));
            check.accept(label("formError").contains("password"), "Responsive: Enter activates login validation without submitting empty credentials");
        } else if (page == 4) {
            CheckBox motion = (CheckBox) scenes.root().lookup("#reducedMotion"); motion.requestFocus();
            motion.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.SPACE,false,false,false,false));
            motion.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.SPACE,false,false,false,false));
            check.accept(!context.reducedMotion(), "Responsive: Space activates the focused reduced-motion checkbox");
            context.reducedMotionProperty().set(true);
        }
    }
    private void decorate() {
        if(page==5){scenes.help();}
        if(page==6){scenes.loading().show("Waiting for the server…");}
        if(page==7){scenes.notifications().show("The server is unavailable. Check its address and try again.",com.quizbattle.client.ui.components.ToastNotification.Tone.ERROR);}
    }
    private void fullscreen() throws Exception {
        check.accept(stage.isFullScreen(),"Responsive: fullscreen "+cases[page]); decorate();layout();checkControls(cases[page]+"-fullscreen");
        capture.save(scenes.root(),"responsive/"+cases[page]+"-fullscreen.png");
        scenes.closeHelp();scenes.loading().hide();scenes.notifications().dismiss();stage.setFullScreen(false);page++;later(this::prepare);
    }
    private void checkLargeRanking(String id) {
        @SuppressWarnings("unchecked") ListView<com.quizbattle.client.model.PlayerScore> list=(ListView<com.quizbattle.client.model.PlayerScore>)scenes.root().lookup("#rankingList");
        check.accept(list.getItems().size()==201 && list.lookupAll(".list-cell").size()<24,"Responsive: 201 standings use bounded visible cells "+id);
        // On the bundled JavaFX 17 runtime scrollTo(last) stops just before the
        // final row. Exercise the actual public scrollbar navigation instead.
        for(Node node:list.lookupAll(".scroll-bar")){
            if(node instanceof ScrollBar bar && bar.getOrientation()==javafx.geometry.Orientation.VERTICAL){bar.setValue(bar.getMax());}
        }
        layout();check.accept(list.getItems().get(200).username().equals(USER),"Responsive: complete server order and long self name retained "+id);
    }
    private void checkControls(String id) {
        List<Node> nodes=new ArrayList<>(); collect(scenes.root(),nodes);
        double width=stage.getScene().getWidth(),height=stage.getScene().getHeight();
        for(Node node:nodes){
            if(!(node instanceof ButtonBase||node instanceof TextInputControl||node instanceof ComboBoxBase<?> )||!visible(node)){continue;}
            Bounds b=node.localToScene(node.getBoundsInLocal());
            if(b.getMaxY()<0||b.getMinY()>height){continue;}
            if(b.getMinX() < -2 || b.getMaxX()>width+2){throw new AssertionError("Horizontal clipping: "+id+" / "+node.getId()+" / "+b);}
        }
        check.accept(true,"Responsive: no horizontal control clipping "+id);
    }
    private static boolean visible(Node n){for(Node p=n;p!=null;p=p.getParent()){if(!p.isVisible()){return false;}}return true;}
    private static void collect(Node n,List<Node> into){into.add(n);if(n instanceof Parent p){p.getChildrenUnmodifiable().forEach(child->collect(child,into));}}
    private void layout(){scenes.root().applyCss();scenes.root().layout();}
    private void server(Step operation,Step next){CompletableFuture.runAsync(()->{try{operation.run();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((v,e)->Platform.runLater(()->run(()->{if(e!=null){throw new AssertionError(e);}next.run();})));}
    private void await(BooleanSupplier condition,Step next){poll(condition,next,System.nanoTime()+TimeUnit.SECONDS.toNanos(6));}
    private void poll(BooleanSupplier condition,Step next,long deadline){run(()->{layout();if(condition.getAsBoolean()){next.run();return;}if(System.nanoTime()>deadline){throw new AssertionError("Responsive fixture timeout: "+page+" / "+service.state().snapshot().notice());}pulse=new PauseTransition(javafx.util.Duration.millis(40));pulse.setOnFinished(e->poll(condition,next,deadline));pulse.play();});}
    private void later(Step step){pulse=new PauseTransition(javafx.util.Duration.millis(120));pulse.setOnFinished(e->run(step));pulse.play();}
    private void run(Step step){if(closed){return;}try{step.run();}catch(Throwable e){close();failure.accept(e);}}
    private void finish(){context.invoke(service::disconnect).whenComplete((v,e)->Platform.runLater(()->run(()->{if(e!=null){throw new AssertionError(e);}close();finished.run();})));}
    @Override public void close(){if(closed){return;}closed=true;if(pulse!=null){pulse.stop();}scenes.close();}
}

package dev.lufi.ui;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.util.Objects;

/**
 * Superficie visual JavaFX para qualquer {@link MediaPlayerBackend}.
 *
 * <p>O componente nao agenda frames nem faz conversao de video: ele somente
 * exibe a imagem entregue pelo backend. A mesma superficie pode ser criada
 * para a janela normal ou para a tela cheia sem expor a implementacao do
 * player para a UI.</p>
 */
final class LuffyVideoView extends StackPane {
    private final ImageView imageView = new ImageView();

    LuffyVideoView() {
        setAlignment(Pos.CENTER);
        setMinSize(0d, 0d);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setMouseTransparent(true);
        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());
        getChildren().add(imageView);
    }

    /** Liga esta superficie ao fluxo de imagens do backend sem copiar frames. */
    void bindImage(ObservableValue<? extends Image> imageSource) {
        imageView.imageProperty().bind(Objects.requireNonNull(imageSource, "imageSource"));
    }
}

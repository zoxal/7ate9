package com.yatty.sevennine.backend.handlers.game;

import com.yatty.sevennine.api.GameResult;
import com.yatty.sevennine.api.dto.game.MoveRejectedResponse;
import com.yatty.sevennine.api.dto.game.MoveRequest;
import com.yatty.sevennine.api.dto.game.NewStateNotification;
import com.yatty.sevennine.backend.data.DatabaseDriver;
import com.yatty.sevennine.backend.model.*;
import com.yatty.sevennine.util.PropertiesProvider;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Processes users' moves.
 * <ul>
 *     <li>If move was illegal, sends {@link MoveRejectedResponse} back to
 *     user.</li>
 *
 *     <li>If move was valid, sends {@link NewStateNotification} for all users with
 *     name of user, that made right move. Also checks if game is over, and if
 *     true, {@link NewStateNotification#lastMove} flag</li>
 * </ul>
 *
 * @author Mike
 * @version 14/03/18
 */
@ChannelHandler.Sharable
public class MoveRequestHandler extends SimpleChannelInboundHandler<MoveRequest> {
    private static final Logger logger = LoggerFactory.getLogger(MoveRequestHandler.class);
    private static ScheduledExecutorService stalemateService =
            Executors.newScheduledThreadPool(1);
    private static long stalemateDelay;
    private static Map<String, Semaphore> messageSemaphoreMap = new ConcurrentHashMap<>();
    
    static {
        try {
            stalemateDelay = Long.valueOf(PropertiesProvider.getGameProperties()
                    .getProperty(PropertiesProvider.Game.STALEMATE_DELAY_MILLISECONDS));
        } catch (IOException | NumberFormatException e) {
            stalemateDelay = 3000;
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                MoveRequest msg) throws Exception {
        LoginedUser user = UserRegistry.checkAndGetLoginedUser(msg.getAuthToken());
        Game game = GameRegistry.getGameById(msg.getGameId());
        
        logger.debug("Accepting move {} from '{}'", msg.getMove(), user.getUser().getGeneratedLogin());
        Semaphore gameMessagesSemaphore;
        if (messageSemaphoreMap.containsKey(game.getId())) {
            gameMessagesSemaphore = messageSemaphoreMap.get(game.getId());
        } else {
            gameMessagesSemaphore = new Semaphore(1);
            messageSemaphoreMap.put(game.getId(), gameMessagesSemaphore);
        }
        gameMessagesSemaphore.acquire();
        if (game.acceptMove(msg.getMove(), user)) {
            processRightMove(game, user, msg);
        } else {
            processWrongMove(user, msg);
        }
        gameMessagesSemaphore.release();
    }

    private void processRightMove(Game game, LoginedUser moveAuthor,
                                  MoveRequest moveRequestMsg) throws InterruptedException {
        NewStateNotification newStateNotification = new NewStateNotification();
        newStateNotification.setMoveWinner(moveAuthor.getUser().getGeneratedLogin());
        newStateNotification.setMoveNumber(game.getMoveNumber());
        
        if (logger.isTraceEnabled()) {
            game.getCurrentPlayers().forEach(p -> {
                logger.trace("Player: {}, Cards left: {}", p.getLoginedUser().getAuthToken(), p.getCards());
            });
        }
        
        if (game.isFinished()) {
            newStateNotification.setLastMove(true);
            EloRating.updateUsersRatings(game.getRegisteredPlayers(), moveAuthor);
            game.getRegisteredPlayers().forEach(u -> DatabaseDriver.updateUserRating(u.getUser()));
            
            GameResult gameResult = new GameResult();
            if (game.getWinner() != null) {
                gameResult.setWinner(game.getWinner().getLoginedUser().getUser().getGeneratedLogin());
            }
            game.getCurrentPlayers().forEach(p -> gameResult.addScore(p.getResult()));
            
            newStateNotification.setGameResult(gameResult);

            GameRegistry.gameFinished(game.getId());
//            CardRotator.stop(game.getId());
        } else {
            newStateNotification.setNextCard(moveRequestMsg.getMove());
//            CardRotator.refresh(game.getId());
        }
        
        if (!game.isFinished() && game.isStalemate()) {
            messageSemaphoreMap.remove(game.getId());
            newStateNotification.setStalemate(true);
    
            logger.debug("Stalemate detected!");
            stalemateService.schedule(() -> {
                game.fixStalemate();
                logger.debug("Stalemate set new card: {}", game.getTopCard());
                NewStateNotification stalemateCardNotification = new NewStateNotification();
                stalemateCardNotification.setStalemate(false);
                stalemateCardNotification.setNextCard(game.getTopCard());
                newStateNotification.setMoveNumber(game.getMoveNumber());
                game.getCurrentLoginedUsers().forEach(u -> u.getChannel().writeAndFlush(stalemateCardNotification));
            }, stalemateDelay, TimeUnit.MILLISECONDS);
        }
    
        game.getCurrentLoginedUsers().forEach(u -> u.getChannel().writeAndFlush(newStateNotification));
    }

    private void processWrongMove(LoginedUser moveAuthor, MoveRequest moveRequestMsg) {
        MoveRejectedResponse response = new MoveRejectedResponse();
        response.setMove(moveRequestMsg.getMove());

        logger.debug("Move {} rejected for '{}'", moveRequestMsg.getMove(), moveAuthor.getUser().getGeneratedLogin());
        moveAuthor.getChannel().writeAndFlush(response);
    }
}
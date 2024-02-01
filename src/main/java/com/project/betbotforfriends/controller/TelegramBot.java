package com.project.betbotforfriends.controller;

import com.project.betbotforfriends.config.BotConfig;
import com.project.betbotforfriends.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.util.*;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private UserBetRepository userBetRepository;
    private final BotConfig config;

    static final String HELP_TEXT = "Это соревнование на интерес среди друзей. Каждый участник будет получать сообщение в чате " +
            "один раз в неделю. В этом сообщении предлагается спрогнозировать победителя в 5 самых громких футбольных матчах недели." +
            "Все результаты будут заноситься в таблицу и будут выявляться победители недели и всего сезона.\n" +
            "Вот какие команды тебе доступны в этом боте:\n" +
            "/start начало работы с ботом\n" +
            "/help снова вызовет помощь\n";
    static final String HELP_TEXT_FOR_ADMIN = "/send - отправить всем участникам бота сообщение\n" +
            "/setevent - добавить событие в базу(формат homeTeam guestTeam htwCoef gtwC dC)\n" +
            "/sendbetstoall - разослать всем участникам бота список ставок\n" +
            "/clearevents - удалить из базы все события\n" +
            "/setresults - ввести результаты матчей(формат resultId1 resultId2 ...)\n" +
            "/stopbets - крайний срок ставок, после этой команды ставки приниматься не будут";

    static final String ERROR_TEXT = "Error occurred: ";


    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "Прожми чтобы начать работу с ботом"));
        listOfCommands.add(new BotCommand("/help", "Что может этот бот?"));
        listOfCommands.add(new BotCommand("/admin", "Команды для админа"));
        listOfCommands.add(new BotCommand("/leaderboard","Показать таблицу лидеров"));
        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Error setting bot command list:" + e.getMessage());//
        }

    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();

            if (message.getText().contains("/send") && message.getChatId().equals(config.getOwnerId())) {
                String textToSend = message.getText().substring(message.getText().indexOf(" "));
                var users = userRepository.findAll();
                for (User user : users) {
                    prepareAndSendMessage(user.getChatId(), textToSend);
                }
            }

            if (message.getText().contains("/setevent") && message.getChatId().equals(config.getOwnerId())) {
                String[] event = message.getText().split(" ");
                registerEvent(event[1], event[2], Double.parseDouble(event[3]), Double.parseDouble(event[4]), Double.parseDouble(event[5]));
                prepareAndSendMessage(message.getChatId(), "Событие добавлено");
            }

            if (message.getText().contains("/sendbetstoall") && message.getChatId().equals(config.getOwnerId())) {
                sendBetsToAll();
            }

            if (message.getText().contains("/clearevents") && message.getChatId().equals(config.getOwnerId())) {
                clearEventsAndBets();
            }

            if (message.getText().contains("/setresults") && message.getChatId().equals(config.getOwnerId())) {
                String[] results = message.getText().split(" ");
                if (results.length != 6) {
                    prepareAndSendMessage(message.getChatId(), "Неверный формат ввода, повторите попытку");
                    return;
                }
                setResults(Integer.parseInt(results[1]), Integer.parseInt(results[2]),
                        Integer.parseInt(results[3]), Integer.parseInt(results[4]), Integer.parseInt(results[5]));
                prepareAndSendMessage(message.getChatId(), "Результаты добавлены, победители выявлены");
            }

            if (message.getText().contains("/stopbets") && message.getChatId().equals(config.getOwnerId())) {
                stopBetsCheck();
            }

            setBotCommands(message);
        } else if (update.hasCallbackQuery()) {
            String[] callBackQuery = update.getCallbackQuery().getData().split(" ");
            int betId = Integer.parseInt(callBackQuery[0]);
            int eventId = Integer.parseInt(callBackQuery[1]);
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            long messageId = update.getCallbackQuery().getMessage().getMessageId();
            registerUserBet(chatId, eventId, betId);

            Event event =  eventRepository.findById(eventId).get();
            String text = event.getHomeTeam() + " - " + event.getGuestTeam() + "\nПринято! Твоя ставка: ";

            switch (betId) {
                case 1: text += "П1"; break;
                case 2: text += "Ничья"; break;
                case 3: text += "П2"; break;
            }
            executeEditMessageText(text, chatId, messageId);


        }
    }

    private void stopBetsCheck() {
        var users = userRepository.findAll();
        var userBets = userBetRepository.findAll();
        Map<Long, Integer> userMap = new HashMap<>();
        for (UserBet userBet : userBets) {
            if (userMap.containsKey(userBet.getUser_id())) {
                userMap.put(userBet.getUser_id(), userMap.get(userBet.getUser_id()) + 1);
            } else {
                userMap.put(userBet.getUser_id(), 1);
            }
        }

        for (User user : users) {
            if (userMap.get(user.getChatId()) == 5) {
                user.setIsDoneBet(true);
                userRepository.save(user);
            } else {
                prepareAndSendMessage(user.getChatId(), "Ставки больше не принимаются, ждем тебя на следующей неделе!");
            }
        }
    }


    private void setBotCommands(Message message) {
        switch (message.getText()){
            case "/start":
                registerUser(message);
                startCommandReceived(message);
                break;
            case "/help":
                prepareAndSendMessage(message.getChatId(), HELP_TEXT);
                break;
            case "/admin":
                if (message.getChatId().equals(config.getOwnerId())) {
                    prepareAndSendMessage(message.getChatId(), HELP_TEXT_FOR_ADMIN);
                } else {
                    prepareAndSendMessage(message.getChatId(), "В доступе отказано, вы не админ!");
                }
                break;
            case "/leaderboard":
                showLeaderboard(message.getChatId());
                break;
            default:
                prepareAndSendMessage(message.getChatId(), "Пока не знаю такой команды, сорян");
        }
    }

    private void prepareAndSendMessage(Long chatId, String textToSend) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(textToSend);
        executeMessage(sendMessage);
    }

    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR_TEXT + e.getMessage());
        }
    }

    private void executeEditMessageText(String text, long chatId, long messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(chatId);
        message.setText(text);
        message.setMessageId((int) messageId);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR_TEXT + e.getMessage());
        }
    }

    private void startCommandReceived(Message message) {
        String firstName = message.getChat().getFirstName();
        String lastName = message.getChat().getLastName();
        String messageToSend = "Привет, " + firstName + " " + lastName + "! Теперь ты участник увлекательного соревнования! " +
                 "Набери /help, чтобы узнать о чем оно";
        prepareAndSendMessage(message.getChatId(), messageToSend);
    }

    private void registerUser(Message message) {
        if (userRepository.findById(message.getChatId()).isEmpty()) {
            var chatId = message.getChatId();
            var chat = message.getChat();
            User user = new User();
            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));
            user.setTotalScore(0.0);
            user.setWeeklyPoints(0.0);

            userRepository.save(user);

            log.info("user saved: " + user);
        }
    }

    private void registerEvent(String homeTeam, String guestTeam, Double homeTeamWinsCoef, Double drawCoef, Double guestTeamWinsCoef){
        Event event = new Event();
        event.setHomeTeam(homeTeam);
        event.setGuestTeam(guestTeam);
        event.setHomeTeamWinsCoef(homeTeamWinsCoef);
        event.setDrawCoef(drawCoef);
        event.setGuestTeamWinsCoef(guestTeamWinsCoef);

//        event.setResult_id(4);
        event.setEventNumber((int) eventRepository.count() + 1);

        eventRepository.save(event);

        log.info("event saved: " + event);
    }

    private void registerUserBet(long chatId, int eventId, int betId) {
        UserBet userBet = new UserBet();
        userBet.setUser_id(chatId);
        userBet.setEvent_id(eventId);
        userBet.setBet_id(betId);
        userBetRepository.save(userBet);

        log.info("bet saved: " + userBet);
    }

    private void doBet(long chatId, Event event) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(event.getHomeTeam() + " - " + event.getGuestTeam());

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();

        var homeTeamWinsButton = new InlineKeyboardButton();
        homeTeamWinsButton.setText("П1(" + event.getHomeTeamWinsCoef() + ")");
        homeTeamWinsButton.setCallbackData("1 " + event.getEvent_id());

        var drawButton = new InlineKeyboardButton();
        drawButton.setText("Ничья(" + event.getDrawCoef() + ")");
        drawButton.setCallbackData("2 " + event.getEvent_id());

        var guestTeamWinsButton = new InlineKeyboardButton();
        guestTeamWinsButton.setText("П2(" + event.getGuestTeamWinsCoef() + ")");
        guestTeamWinsButton.setCallbackData("3 "+ event.getEvent_id());

        rowInLine.add(homeTeamWinsButton);
        rowInLine.add(drawButton);
        rowInLine.add(guestTeamWinsButton);

        rowsInLine.add(rowInLine);
        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);

        executeMessage(message);
    }

    private void sendBetsToAll() {
        var users = userRepository.findAll();
        var events = eventRepository.findAll();

        for (User user : users) {
            for (Event event : events) {
                doBet(user.getChatId(), event);
            }
        }
    }

    private void clearEventsAndBets() {
        eventRepository.deleteAll();
        userBetRepository.deleteAll();
        prepareAndSendMessage(config.getOwnerId(), "События удалены.");
    }

    private void setResults(int result1, int result2, int result3, int result4, int result5) {
        var events = eventRepository.findAll();
        var users = userRepository.findAll();
        var userBets = userBetRepository.findAll();


        for (Event event : events) {

            switch (event.getEventNumber()) {
                case 1: event.setResult_id(result1); break;
                case 2: event.setResult_id(result2); break;
                case 3: event.setResult_id(result3); break;
                case 4: event.setResult_id(result4); break;
                case 5: event.setResult_id(result5); break;
            }
        }

        eventRepository.saveAll(events);

        for (User user : users) {
            user.setWeeklyPoints(0.0);
            userRepository.save(user);
        }


        for (UserBet userBet : userBets) {
            Event event = eventRepository.findById(userBet.getEvent_id()).get();
            double resultCoef;
            if (userBet.getBet_id().equals(event.getResult_id())) {
                switch (event.getResult_id()) {
                    case 1: resultCoef = event.getHomeTeamWinsCoef(); break;
                    case 2: resultCoef = event.getDrawCoef(); break;
                    case 3: resultCoef = event.getGuestTeamWinsCoef(); break;
                    default: resultCoef = 0.0;
                }


                User user = userRepository.findById(userBet.getUser_id()).get();
                user.setWeeklyPoints(user.getWeeklyPoints() + resultCoef);
                user.setTotalScore(user.getTotalScore() + resultCoef);
                userRepository.save(user);
            }
        }
    }

    private void showLeaderboard(long chatId){
        var users = userRepository.findAll();
        List<User> userList = new ArrayList<>();
        StringBuilder builder = new StringBuilder();

        for (User user : users) {
            userList.add(user);
        }

        userList.sort(new Comparator<User>() {
            @Override
            public int compare(User o1, User o2) {
                return (int) (o2.getTotalScore() - o1.getTotalScore());
            }
        });

        builder.append("Таблица лидеров за всё время:\n");

        for (User user : userList) {
            builder.append(userList.indexOf(user) + 1);
            builder.append(". ");
            builder.append(user.getUserName());
            builder.append(" - ");
            builder.append(String.format("%.1f", user.getTotalScore()));
            builder.append("\n");
        }

        builder.append("\n\n\nТаблица лидеров за неделю:\n");

        userList.sort(new Comparator<User>() {
            @Override
            public int compare(User o1, User o2) {
                return (int) (o2.getWeeklyPoints() - o1.getWeeklyPoints());
            }
        });

        for (User user : userList) {
            builder.append(userList.indexOf(user) + 1);
            builder.append(". ");
            builder.append(user.getUserName());
            builder.append(" - ");
            builder.append(String.format("%.1f", user.getWeeklyPoints()));
            builder.append("\n");
        }

        prepareAndSendMessage(chatId, builder.toString());

    }
}



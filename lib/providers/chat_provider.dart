import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/network/base_client.dart';
import '../core/result/result.dart';
import '../features/chat/data/chat_client.dart';
import '../features/chat/data/chat_repository_impl.dart';
import '../features/chat/domain/chat_repository.dart';
import '../features/chat/domain/send_chat_message_usecase.dart';
import '../features/chat/domain/send_document_chat_message_usecase.dart';
import '../features/chat/presentation/chat_state.dart';
import '../models/message_model.dart';

class DocumentContext {
  final String fileName;
  final String text;

  const DocumentContext({required this.fileName, required this.text});
}

final documentContextProvider = StateProvider<DocumentContext?>((ref) => null);

final httpClientProvider = Provider<http.Client>((ref) => http.Client());
final baseClientProvider = Provider<BaseClient>((ref) => BaseClient(ref.watch(httpClientProvider)));
final chatClientProvider = Provider<ChatClient>((ref) => ChatClient(ref.watch(baseClientProvider)));
final chatRepositoryProvider = Provider<ChatRepository>((ref) => ChatRepositoryImpl(ref.watch(chatClientProvider)));
final sendChatMessageUseCaseProvider = Provider<SendChatMessageUseCase>((ref) => SendChatMessageUseCase(ref.watch(chatRepositoryProvider)));
final sendDocumentChatMessageUseCaseProvider = Provider<SendDocumentChatMessageUseCase>((ref) => SendDocumentChatMessageUseCase(ref.watch(chatRepositoryProvider)));

final chatStateProvider = StateProvider<ChatState>(
  (ref) => ChatState(messages: const []),
);

class ChatNotifier extends AsyncNotifier<List<Message>> {
  static const String _initialGreetingId = 'initial_greeting';
  static const String _persistenceKey = 'chat_messages_v1';

  @override
  FutureOr<List<Message>> build() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_persistenceKey);

    List<Message> messages;
    if (raw == null || raw.isEmpty) {
      messages = [_greeting()];
    } else {
      final decoded = (jsonDecode(raw) as List)
          .cast<Map<String, dynamic>>()
          .map(_fromJson)
          .toList();
      messages = decoded.isEmpty ? [_greeting()] : decoded;
    }

    ref.read(chatStateProvider.notifier).state = ChatState(messages: messages);
    return messages;
  }

  Message _greeting() => Message(
        id: _initialGreetingId,
        text: "Hello! I'm Stremini AI. How can I help you today?",
        type: MessageType.bot,
        timestamp: DateTime.now(),
      );

  Map<String, dynamic> _toJson(Message m) => {
        'id': m.id,
        'text': m.text,
        'type': m.type.name,
        'timestamp': m.timestamp.toIso8601String(),
      };

  Message _fromJson(Map<String, dynamic> j) {
    final typeName = (j['type'] as String?) ?? MessageType.bot.name;
    final type = MessageType.values.firstWhere(
      (e) => e.name == typeName,
      orElse: () => MessageType.bot,
    );
    return Message(
      id: (j['id'] ?? '').toString(),
      text: (j['text'] ?? '').toString(),
      type: type,
      timestamp: DateTime.tryParse((j['timestamp'] ?? '').toString()) ?? DateTime.now(),
    );
  }

  Future<void> _persist(List<Message> messages) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _persistenceKey,
      jsonEncode(messages.map(_toJson).toList()),
    );
    ref.read(chatStateProvider.notifier).state =
        ref.read(chatStateProvider).copyWith(messages: messages, errorMessage: null);
  }

  List<Map<String, dynamic>> _getHistory(List<Message> messages) {
    final history = <Map<String, dynamic>>[];
    for (final msg in messages) {
      if (msg.id == _initialGreetingId ||
          msg.type == MessageType.typing ||
          msg.type == MessageType.documentBanner ||
          msg.text.startsWith('❌') ||
          msg.text.startsWith('⚠️')) {
        continue;
      }
      history.add({
        'role': msg.type == MessageType.user ? 'user' : 'assistant',
        'content': msg.text,
      });
    }
    return history.length > 100 ? history.sublist(history.length - 100) : history;
  }

  Future<void> sendMessage(
    String text, {
    String? attachment,
    String? mimeType,
    String? fileName,
  }) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty && attachment == null) return;

    final current = [...(state.value ?? <Message>[])];
    final displayText = trimmed.isEmpty ? 'Sent an attachment: $fileName' : trimmed;
    final userMessage = Message(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      text: displayText,
      type: MessageType.user,
      timestamp: DateTime.now(),
    );

    final List<Message> next = [...current.where((m) => m.id != _initialGreetingId), userMessage];
    state = AsyncValue.data(next);
    addTypingIndicator();
    ref.read(chatStateProvider.notifier).state =
        ref.read(chatStateProvider).copyWith(isLoading: true, messages: state.value ?? <Message>[]);

    try {
      final history = _getHistory(next);
      final docCtx = ref.read(documentContextProvider);

      final result = (docCtx != null && trimmed.isNotEmpty)
          ? await ref.read(sendDocumentChatMessageUseCaseProvider)(
              documentText: docCtx.text,
              question: trimmed,
              history: history,
            )
          : await ref.read(sendChatMessageUseCaseProvider)(
              message: trimmed,
              attachment: attachment,
              mimeType: mimeType,
              fileName: fileName,
              history: history,
            );

      removeTypingIndicator();
      final List<Message> updated = [
        ...(state.value ?? <Message>[]),
        Message(
          id: DateTime.now().millisecondsSinceEpoch.toString(),
          text: result.when(
            success: (reply) => reply,
            failure: (failure) => '⚠️ ${failure.message}',
          ),
          type: MessageType.bot,
          timestamp: DateTime.now(),
        ),
      ];
      state = AsyncValue.data(updated);
      await _persist(updated);
      ref.read(chatStateProvider.notifier).state = ref.read(chatStateProvider).copyWith(
            isLoading: false,
            errorMessage: result.when(success: (_) => null, failure: (f) => f.message),
          );
    } catch (e) {
      removeTypingIndicator();
      final List<Message> updated = [
        ...(state.value ?? <Message>[]),
        Message(
          id: DateTime.now().toString(),
          text: '⚠️ ${UnknownFailure(e.toString()).message}',
          type: MessageType.bot,
          timestamp: DateTime.now(),
        ),
      ];
      state = AsyncValue.data(updated);
      await _persist(updated);
      ref.read(chatStateProvider.notifier).state =
          ref.read(chatStateProvider).copyWith(isLoading: false, errorMessage: e.toString());
    }
  }

  void loadDocument(DocumentContext doc) {
    ref.read(documentContextProvider.notifier).state = doc;
    final banner = Message(
      id: 'doc_${DateTime.now().millisecondsSinceEpoch}',
      text: '📄 Document loaded: ${doc.fileName}\nAsk anything about it. Tap × in the banner to clear.',
      type: MessageType.documentBanner,
      timestamp: DateTime.now(),
    );

    final List<Message> updated = [
      ...(state.value ?? <Message>[]).where((m) => m.id != _initialGreetingId),
      banner,
    ];
    state = AsyncValue.data(updated);
    _persist(updated);
    ref.read(chatStateProvider.notifier).state =
        ref.read(chatStateProvider).copyWith(activeDocumentName: doc.fileName);
  }

  void clearDocument() {
    ref.read(documentContextProvider.notifier).state = null;
    final List<Message> updated = [
      ...(state.value ?? <Message>[]),
      Message(
        id: 'doc_clear_${DateTime.now().millisecondsSinceEpoch}',
        text: '📄 Document cleared. Back to normal chat.',
        type: MessageType.bot,
        timestamp: DateTime.now(),
      ),
    ];
    state = AsyncValue.data(updated);
    _persist(updated);
    ref.read(chatStateProvider.notifier).state =
        ref.read(chatStateProvider).copyWith(activeDocumentName: null);
  }

  void addTypingIndicator() {
    final current = state.value ?? <Message>[];
    if (current.any((m) => m.type == MessageType.typing)) return;
    state = AsyncValue.data([
      ...current,
      Message(id: 'typing', text: '...', type: MessageType.typing, timestamp: DateTime.now()),
    ]);
  }

  void removeTypingIndicator() {
    final current = state.value ?? <Message>[];
    state = AsyncValue.data(current.where((m) => m.type != MessageType.typing).toList());
  }

  Future<void> clearChat() async {
    ref.read(documentContextProvider.notifier).state = null;
    final List<Message> updated = [_greeting()];
    state = AsyncValue.data(updated);
    await _persist(updated);
    ref.read(chatStateProvider.notifier).state = ChatState(messages: updated);
  }
}

final chatNotifierProvider =
    AsyncNotifierProvider<ChatNotifier, List<Message>>(ChatNotifier.new);
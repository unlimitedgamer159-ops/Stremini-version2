import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:stremini_chatbot/providers/scanner_provider.dart';
import 'core/native/android_native_bridge_service.dart';
import 'core/native/native_bridge_service.dart';
import 'core/theme/app_theme.dart';
import 'screens/home/home_screen.dart';
import 'utils/session_lifecycle_manager.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ProviderScope(
      child: _AppWithContainer(),
    );
  }
}

class _AppWithContainer extends ConsumerStatefulWidget {
  const _AppWithContainer();

  @override
  ConsumerState<_AppWithContainer> createState() => _AppWithContainerState();
}

class _AppWithContainerState extends ConsumerState<_AppWithContainer> {
  static ProviderContainer? globalContainer;
  final NativeBridgeService _nativeBridge = AndroidNativeBridgeService();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_AppWithContainerState.globalContainer == null) {
      _AppWithContainerState.globalContainer =
          ProviderScope.containerOf(context);
      _setupScannerListeners();
    }
  }

  void _setupScannerListeners() {
    if (_AppWithContainerState.globalContainer == null) return;

    _nativeBridge.initialize(onEvent: (method) async {
      final notifier = _AppWithContainerState.globalContainer!.read(scannerStateProvider.notifier);
      switch (method) {
        case 'startScanner':
          await notifier.startScanning();
          break;
        case 'stopScanner':
          await notifier.stopScanning();
          break;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Stremini AI',
      theme: AppTheme.darkTheme,
      home: const SessionLifecycleManager(
        child: HomeScreen(),
      ),
    );
  }
}

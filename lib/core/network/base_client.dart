import 'dart:convert';
import 'package:http/http.dart' as http;

class BaseClient {
  const BaseClient(this._httpClient);

  final http.Client _httpClient;

  Future<Map<String, dynamic>> postJson(
    Uri uri,
    Map<String, dynamic> body,
  ) async {
    final response = await _httpClient.post(
      uri,
      headers: const {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: jsonEncode(body),
    );

    final dynamic decoded = response.body.isEmpty ? {} : jsonDecode(response.body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception(decoded is Map<String, dynamic>
          ? (decoded['error'] ?? 'HTTP ${response.statusCode}')
          : 'HTTP ${response.statusCode}');
    }

    return decoded is Map<String, dynamic>
        ? decoded
        : <String, dynamic>{'data': decoded.toString()};
  }
}

#include <string>

namespace FileIO {
  std::string readline(const char *path);
  int readline(const char *path);

  void writeline(const char *path, const std::string& data);
  void writeline(const char *path, const int data);
} // namespace FileIO
